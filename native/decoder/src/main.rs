//! Sequoia Images decoder helper.
//!
//! The helper prepares the fixed FFmpeg decoder inside Wasmtime and applies the OS sandbox
//! before it reads anything, so it can start while the caller is still downloading. Stdin then
//! carries a big-endian `u32` length and that many media bytes, which are read straight into
//! guest memory; stdout returns the validated pixel stream described in [`validate`].

use seqimages_decoder::{engine, sandbox};
use std::io::{Read, Write};
use std::ops::Range;
use std::process::ExitCode;
use std::sync::mpsc;
use std::thread::JoinHandle;
use std::time::Duration;
use wasmtime::{Engine, Linker, Memory, Module, Store, StoreLimits, StoreLimitsBuilder, TypedFunc};

const MAX_INPUT: usize = 8 * 1024 * 1024;
const MAX_OUTPUT: usize = 48_001_616;
const MAGIC: u32 = 0x5349_4d31;
const DECODE_DEADLINE: Duration = Duration::from_secs(15);
const FUEL: u64 = 10_000_000_000;

#[cfg(all(target_os = "windows", target_arch = "x86_64"))]
const AOT: &[u8] = include_bytes!("../../wasm/windows-x86_64.cwasm");
#[cfg(all(target_os = "windows", target_arch = "aarch64"))]
const AOT: &[u8] = include_bytes!("../../wasm/windows-aarch64.cwasm");
#[cfg(all(target_os = "linux", target_arch = "x86_64"))]
const AOT: &[u8] = include_bytes!("../../wasm/linux-x86_64.cwasm");
#[cfg(all(target_os = "linux", target_arch = "aarch64"))]
const AOT: &[u8] = include_bytes!("../../wasm/linux-aarch64.cwasm");
#[cfg(all(target_os = "macos", target_arch = "x86_64"))]
const AOT: &[u8] = include_bytes!("../../wasm/macos-x86_64.cwasm");
#[cfg(all(target_os = "macos", target_arch = "aarch64"))]
const AOT: &[u8] = include_bytes!("../../wasm/macos-aarch64.cwasm");

struct State {
    limits: StoreLimits,
}

fn store(engine: &Engine) -> wasmtime::Result<Store<State>> {
    let mut store = Store::new(
        engine,
        State {
            limits: StoreLimitsBuilder::new()
                .memory_size(192 * 1024 * 1024)
                .memories(1)
                .tables(1)
                .table_elements(65536)
                .instances(1)
                .trap_on_grow_failure(true)
                .build(),
        },
    );
    store.limiter(|state| &mut state.limits);
    store.set_fuel(FUEL)?;
    store.set_epoch_deadline(1);
    Ok(store)
}

fn restricted_linker(engine: &Engine) -> wasmtime::Result<Linker<State>> {
    let mut linker = Linker::new(engine);
    // Satisfy libc imports without exposing the host filesystem or network.
    linker.func_wrap(
        "wasi_snapshot_preview1",
        "proc_exit",
        |_: i32| -> wasmtime::Result<()> { Err(wasmtime::Error::msg("decoder exit")) },
    )?;
    linker.func_wrap("wasi_snapshot_preview1", "fd_close", |_: i32| -> i32 { 8 })?;
    linker.func_wrap(
        "wasi_snapshot_preview1",
        "fd_seek",
        |_: i32, _: i64, _: i32, _: i32| -> i32 { 8 },
    )?;
    linker.func_wrap(
        "wasi_snapshot_preview1",
        "fd_write",
        |_: i32, _: i32, _: i32, _: i32| -> i32 { 8 },
    )?;
    linker.func_wrap(
        "wasi_snapshot_preview1",
        "fd_fdstat_get",
        |_: i32, _: i32| -> i32 { 8 },
    )?;
    linker.func_wrap(
        "wasi_snapshot_preview1",
        "clock_time_get",
        |_: i32, _: i64, _: i32| -> i32 { 76 },
    )?;
    linker.func_wrap(
        "wasi_snapshot_preview1",
        "environ_get",
        |_: i32, _: i32| -> i32 { 0 },
    )?;
    linker.func_wrap(
        "wasi_snapshot_preview1",
        "environ_sizes_get",
        |mut caller: wasmtime::Caller<'_, State>, a: i32, b: i32| -> i32 {
            let Some(mem) = caller
                .get_export("memory")
                .and_then(|export| export.into_memory())
            else {
                return 76;
            };
            if mem.write(&mut caller, a as u32 as usize, &[0; 4]).is_err()
                || mem.write(&mut caller, b as u32 as usize, &[0; 4]).is_err()
            {
                76
            } else {
                0
            }
        },
    )?;
    linker.func_wrap(
        "wasi_snapshot_preview1",
        "fd_fdstat_set_flags",
        |_: i32, _: i32| -> i32 { 8 },
    )?;
    linker.func_wrap(
        "wasi_snapshot_preview1",
        "fd_prestat_get",
        |_: i32, _: i32| -> i32 { 8 },
    )?;
    linker.func_wrap(
        "wasi_snapshot_preview1",
        "fd_prestat_dir_name",
        |_: i32, _: i32, _: i32| -> i32 { 8 },
    )?;
    linker.func_wrap(
        "wasi_snapshot_preview1",
        "fd_read",
        |_: i32, _: i32, _: i32, _: i32| -> i32 { 8 },
    )?;
    linker.func_wrap(
        "wasi_snapshot_preview1",
        "fd_readdir",
        |_: i32, _: i32, _: i32, _: i64, _: i32| -> i32 { 8 },
    )?;
    linker.func_wrap(
        "wasi_snapshot_preview1",
        "path_filestat_get",
        |_: i32, _: i32, _: i32, _: i32, _: i32| -> i32 { 76 },
    )?;
    linker.func_wrap(
        "wasi_snapshot_preview1",
        "path_open",
        |_: i32, _: i32, _: i32, _: i32, _: i32, _: i64, _: i64, _: i32, _: i32| -> i32 { 76 },
    )?;
    linker.func_wrap(
        "wasi_snapshot_preview1",
        "poll_oneoff",
        |_: i32, _: i32, _: i32, _: i32| -> i32 { 76 },
    )?;

    Ok(linker)
}

/// Checks the pixel stream: `MAGIC, width, height, count`, then per frame a duration in
/// milliseconds and `width * height` ARGB pixels, all big-endian, with nothing after.
fn validate(bytes: &[u8]) -> wasmtime::Result<()> {
    fn value(bytes: &[u8], at: usize) -> wasmtime::Result<usize> {
        let word = at
            .checked_add(4)
            .and_then(|end| bytes.get(at..end))
            .and_then(|word| <[u8; 4]>::try_from(word).ok())
            .ok_or_else(|| wasmtime::Error::msg("truncated pixels"))?;
        Ok(u32::from_be_bytes(word) as usize)
    }
    let reject = |reason: &str| Err(wasmtime::Error::msg(reason.to_owned()));
    if bytes.len() < 16 || bytes.len() > MAX_OUTPUT || value(bytes, 0)? != MAGIC as usize {
        return reject("invalid pixel header");
    }
    let (width, height, frames) = (value(bytes, 4)?, value(bytes, 8)?, value(bytes, 12)?);
    if width == 0
        || height == 0
        || width > 256
        || height > 256
        || frames == 0
        || frames > 400
        || width * height * frames > 12_000_000
    {
        return reject("invalid pixel bounds");
    }
    let stride = 4 + 4 * width * height;
    if bytes.len() != 16 + frames * stride {
        return reject("invalid pixel length");
    }
    for frame in 0..frames {
        let ms = value(bytes, 16 + frame * stride)?;
        if ms == 0 || ms > 655_350 {
            return reject("invalid pixel timing");
        }
    }
    Ok(())
}

/// Loads the decoder that `examples/precompile.rs` compiled ahead of time for this platform.
///
/// # Errors
///
/// Returns an error if the artifact was produced by another Wasmtime version or configuration.
fn load_decoder(engine: &Engine) -> wasmtime::Result<Module> {
    // SAFETY: `Module::deserialize` trusts its input to be Wasmtime's own compiler output. The
    // only input here is `AOT`, embedded at build time from the bundled artifact compiled from
    // the fixed decoder.wasm with `engine::config()`; this function never sees media or other
    // runtime bytes. Wasmtime still checks that the artifact matches this engine's version and
    // settings, which the `embedded_decoder_matches_the_runtime_engine` test exercises.
    unsafe { Module::deserialize(engine, AOT) }
}

/// The instantiated decoder, ready to receive media.
struct Decoder {
    store: Store<State>,
    memory: Memory,
    allocate_input: TypedFunc<i32, i32>,
    decode_media: TypedFunc<(i32, i32), i32>,
    output_ptr: TypedFunc<(), i32>,
    output_len: TypedFunc<(), i32>,
}

impl Decoder {
    fn new(engine: &Engine, module: &Module) -> wasmtime::Result<Self> {
        let mut store = store(engine)?;
        let instance = restricted_linker(engine)?.instantiate(&mut store, module)?;
        if let Some(init) = instance.get_func(&mut store, "_initialize") {
            init.typed::<(), ()>(&store)?.call(&mut store, ())?;
        }
        Ok(Self {
            memory: instance
                .get_memory(&mut store, "memory")
                .ok_or_else(|| wasmtime::Error::msg("missing memory"))?,
            allocate_input: instance.get_typed_func(&mut store, "allocate_input")?,
            decode_media: instance.get_typed_func(&mut store, "decode_media")?,
            output_ptr: instance.get_typed_func(&mut store, "output_ptr")?,
            output_len: instance.get_typed_func(&mut store, "output_len")?,
            store,
        })
    }

    /// Reads one length-prefixed media file from `input` straight into guest memory and
    /// returns its guest address and length.
    fn receive(&mut self, input: &mut impl Read) -> wasmtime::Result<(i32, i32)> {
        let mut header = [0; 4];
        input.read_exact(&mut header)?;
        let len = u32::from_be_bytes(header) as usize;
        if len == 0 || len > MAX_INPUT {
            return Err(wasmtime::Error::msg("input budget"));
        }
        let guest_len = i32::try_from(len)?;
        let ptr = self.allocate_input.call(&mut self.store, guest_len)? as u32 as usize;
        if ptr == 0 {
            return Err(wasmtime::Error::msg("allocation failed"));
        }
        let target = ptr
            .checked_add(len)
            .and_then(|end| self.memory.data_mut(&mut self.store).get_mut(ptr..end))
            .ok_or_else(|| wasmtime::Error::msg("input range"))?;
        input.read_exact(target)?;
        if input.read(&mut [0; 1])? != 0 {
            return Err(wasmtime::Error::msg("extra input"));
        }
        Ok((i32::try_from(ptr)?, guest_len))
    }

    /// Decodes received media and returns the guest range holding the pixel stream.
    fn decode(&mut self, (ptr, len): (i32, i32)) -> wasmtime::Result<Range<usize>> {
        if self.decode_media.call(&mut self.store, (ptr, len))? != 0 {
            return Err(wasmtime::Error::msg("media rejected"));
        }
        let start = self.output_ptr.call(&mut self.store, ())? as u32 as usize;
        let len = self.output_len.call(&mut self.store, ())? as u32 as usize;
        if !(16..=MAX_OUTPUT).contains(&len) {
            return Err(wasmtime::Error::msg("output budget"));
        }
        let end = start
            .checked_add(len)
            .ok_or_else(|| wasmtime::Error::msg("output overflow"))?;
        Ok(start..end)
    }

    fn bytes(&self, range: Range<usize>) -> wasmtime::Result<&[u8]> {
        self.memory
            .data(&self.store)
            .get(range)
            .ok_or_else(|| wasmtime::Error::msg("output range"))
    }
}

/// Interrupts guest code [`DECODE_DEADLINE`] after decoding begins.
///
/// The thread is started before the sandbox, which forbids creating threads, and waits for a
/// start signal so the deadline does not include time spent waiting for the caller's download.
struct Deadline {
    signal: Option<mpsc::Sender<()>>,
    thread: Option<JoinHandle<()>>,
}

impl Deadline {
    fn spawn(engine: Engine) -> Self {
        let (signal, events) = mpsc::channel::<()>();
        let thread = std::thread::spawn(move || {
            if events.recv().is_ok()
                && matches!(
                    events.recv_timeout(DECODE_DEADLINE),
                    Err(mpsc::RecvTimeoutError::Timeout)
                )
            {
                engine.increment_epoch();
            }
        });
        Self {
            signal: Some(signal),
            thread: Some(thread),
        }
    }

    fn begin(&self) {
        if let Some(signal) = &self.signal {
            // A send can only fail if the thread is gone, in which case nothing can expire.
            let _ = signal.send(());
        }
    }
}

impl Drop for Deadline {
    fn drop(&mut self) {
        // Disconnecting ends the wait without interrupting anything.
        self.signal = None;
        if let Some(thread) = self.thread.take() {
            let _ = thread.join();
        }
    }
}

fn run() -> wasmtime::Result<()> {
    let engine = engine::engine()?;
    let module = load_decoder(&engine)?;
    let mut decoder = Decoder::new(&engine, &module)?;
    let deadline = Deadline::spawn(engine.clone());
    // Nothing untrusted has been read yet; everything after this line is confined.
    sandbox::restrict()?;
    let input = decoder.receive(&mut std::io::stdin().lock())?;
    deadline.begin();
    let output = decoder.decode(input);
    drop(deadline);
    let pixels = decoder.bytes(output?)?;
    validate(pixels)?;
    let mut out = std::io::stdout().lock();
    out.write_all(pixels)?;
    out.flush()?;
    Ok(())
}

fn main() -> ExitCode {
    match run() {
        Ok(()) => ExitCode::SUCCESS,
        Err(error) => {
            eprintln!("Image could not be decoded within safety limits: {error}");
            ExitCode::FAILURE
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    const ONE_PIXEL_PNG: [u8; 69] = [
        0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 0x00, 0x00, 0x00, 0x0d, 0x49, 0x48, 0x44,
        0x52, 0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01, 0x08, 0x02, 0x00, 0x00, 0x00, 0x90,
        0x77, 0x53, 0xde, 0x00, 0x00, 0x00, 0x0c, 0x49, 0x44, 0x41, 0x54, 0x78, 0x9c, 0x63, 0xf8,
        0xcf, 0xf0, 0x1f, 0x00, 0x04, 0x00, 0x01, 0xff, 0x22, 0x0a, 0x3a, 0xf0, 0x00, 0x00, 0x00,
        0x00, 0x49, 0x45, 0x4e, 0x44, 0xae, 0x42, 0x60, 0x82,
    ];

    #[test]
    fn protocol_rejects_overflow_truncation_and_extra_bytes() {
        assert!(validate(&[0; 16]).is_err());
        let mut b = Vec::new();
        for v in [0x53494d31u32, 1, 1, 1, 20, 0xff123456] {
            b.extend(v.to_be_bytes())
        }
        assert!(validate(&b).is_ok());
        b.push(0);
        assert!(validate(&b).is_err());
    }

    #[test]
    fn embedded_decoder_matches_the_runtime_engine() {
        let engine = engine::engine().unwrap();
        load_decoder(&engine).expect("bundled AOT must match engine::config()");
    }

    /// The one-pixel PNG framed as helper input, optionally with trailing garbage.
    fn png_input(trailing: bool) -> Vec<u8> {
        let mut input = (ONE_PIXEL_PNG.len() as u32).to_be_bytes().to_vec();
        input.extend(ONE_PIXEL_PNG);
        if trailing {
            input.push(0);
        }
        input
    }

    fn embedded_decoder() -> (Engine, Decoder) {
        let engine = engine::engine().unwrap();
        let module = load_decoder(&engine).unwrap();
        let decoder = Decoder::new(&engine, &module).unwrap();
        (engine, decoder)
    }

    #[test]
    fn embedded_decoder_streams_media_in_and_pixels_out() {
        let (_engine, mut decoder) = embedded_decoder();
        let received = decoder.receive(&mut png_input(false).as_slice()).unwrap();
        let range = decoder.decode(received).unwrap();
        let pixels = decoder.bytes(range).unwrap();
        validate(pixels).unwrap();
        assert_eq!(&pixels[4..16], &[0, 0, 0, 1, 0, 0, 0, 1, 0, 0, 0, 1]);
    }

    #[test]
    fn trailing_input_is_rejected_before_decoding() {
        let (_engine, mut decoder) = embedded_decoder();
        assert!(decoder.receive(&mut png_input(true).as_slice()).is_err());
    }

    #[test]
    fn infinite_loop_exhausts_fuel() {
        let engine = engine::engine().unwrap();
        let module = Module::new(
            &engine,
            wat::parse_str("(module (func (export \"run\") (loop br 0)))").unwrap(),
        )
        .unwrap();
        let mut store = store(&engine).unwrap();
        store.set_fuel(1000).unwrap();
        let instance = wasmtime::Instance::new(&mut store, &module, &[]).unwrap();
        assert!(
            instance
                .get_typed_func::<(), ()>(&mut store, "run")
                .unwrap()
                .call(&mut store, ())
                .is_err()
        );
    }

    #[test]
    fn memory_growth_is_bounded() {
        let engine = engine::engine().unwrap();
        let module = Module::new(
            &engine,
            wat::parse_str(
                "(module (memory 1) (func (export \"run\") (result i32) i32.const 4000 memory.grow))",
            )
            .unwrap(),
        )
        .unwrap();
        let mut store = store(&engine).unwrap();
        let instance = wasmtime::Instance::new(&mut store, &module, &[]).unwrap();
        assert!(
            instance
                .get_typed_func::<(), i32>(&mut store, "run")
                .unwrap()
                .call(&mut store, ())
                .is_err()
        );
    }

    #[test]
    fn filesystem_import_cannot_instantiate() {
        let engine = engine::engine().unwrap();
        let module = Module::new(
            &engine,
            wat::parse_str("(module (import \"wasi_snapshot_preview1\" \"path_open\" (func)))")
                .unwrap(),
        )
        .unwrap();
        let mut store = store(&engine).unwrap();
        assert!(
            restricted_linker(&engine)
                .unwrap()
                .instantiate(&mut store, &module)
                .is_err()
        );
    }

    #[test]
    fn filesystem_calls_are_inert() {
        let engine = engine::engine().unwrap();
        let mut store = store(&engine).unwrap();
        let linker = restricted_linker(&engine).unwrap();
        let path_open = linker
            .get(&mut store, "wasi_snapshot_preview1", "path_open")
            .unwrap()
            .into_func()
            .unwrap();
        let path_open = path_open
            .typed::<(i32, i32, i32, i32, i32, i64, i64, i32, i32), i32>(&store)
            .unwrap();
        assert_eq!(
            76,
            path_open
                .call(&mut store, (3, 0, 0, 0, 0, 0, 0, 0, 0))
                .unwrap()
        );
    }

    #[test]
    fn network_import_is_unavailable() {
        let engine = engine::engine().unwrap();
        let module = Module::new(
            &engine,
            wat::parse_str("(module (import \"wasi_snapshot_preview1\" \"sock_open\" (func)))")
                .unwrap(),
        )
        .unwrap();
        let mut store = store(&engine).unwrap();
        assert!(
            restricted_linker(&engine)
                .unwrap()
                .instantiate(&mut store, &module)
                .is_err()
        );
    }

    #[test]
    fn external_deadline_interrupts_a_loop() {
        let engine = engine::engine().unwrap();
        let module = Module::new(
            &engine,
            wat::parse_str("(module (func (export \"run\") (loop br 0)))").unwrap(),
        )
        .unwrap();
        let mut store = store(&engine).unwrap();
        store.set_fuel(u64::MAX).unwrap();
        let instance = wasmtime::Instance::new(&mut store, &module, &[]).unwrap();
        let other = engine.clone();
        let timer = std::thread::spawn(move || {
            std::thread::sleep(std::time::Duration::from_millis(20));
            other.increment_epoch();
        });
        let error = instance
            .get_typed_func::<(), ()>(&mut store, "run")
            .unwrap()
            .call(&mut store, ())
            .unwrap_err();
        timer.join().unwrap();
        assert!(matches!(
            error.downcast_ref::<wasmtime::Trap>(),
            Some(wasmtime::Trap::Interrupt)
        ));
    }
}
