//! Sequoia Images decoder helper.
//!
//! The helper prepares the fixed FFmpeg decoder inside Wasmtime and applies the OS sandbox
//! before it reads anything, so it can start while the caller is still downloading. Stdin then
//! carries a big-endian `u32` length and that many media bytes, which are read straight into
//! guest memory; stdout returns the validated pixel stream described in [`validate`].

use seqimages_decoder::sandbox;
use std::io::{Read, Write};
use std::process::ExitCode;
use std::sync::mpsc;
use std::time::Duration;
use wasmtime::error::Context;
use wasmtime::{
    Caller, Engine, Extern, Linker, Memory, Module, Store, StoreLimits, StoreLimitsBuilder,
    TypedFunc, bail, ensure,
};

const MAX_INPUT: usize = 8 * 1024 * 1024;
/// Header, 400 frame durations and 12 million ARGB pixels.
const MAX_OUTPUT: usize = 16 + 400 * 4 + 12_000_000 * 4;
const MAGIC: u32 = 0x5349_4d31;
const DECODE_DEADLINE: Duration = Duration::from_secs(15);
const FUEL: u64 = 10_000_000_000;
const WASI: &str = "wasi_snapshot_preview1";
// WASI errno values the inert stubs return.
const EBADF: i32 = 8;
const ENOTCAPABLE: i32 = 76;

/// The decoder guest, precompiled for this target by `native/build-native.py`.
const AOT: &[u8] = include_bytes!(env!(
    "SEQIMAGES_AOT_PATH",
    "build with native/build-native.py, which precompiles the decoder guest for this target"
));

fn store(engine: &Engine) -> wasmtime::Result<Store<StoreLimits>> {
    let limits = StoreLimitsBuilder::new()
        .memory_size(192 * 1024 * 1024)
        .memories(1)
        .tables(1)
        .table_elements(65536)
        .instances(1)
        .trap_on_grow_failure(true)
        .build();
    let mut store = Store::new(engine, limits);
    store.limiter(|limits| limits);
    store.set_fuel(FUEL)?;
    store.set_epoch_deadline(1);
    Ok(store)
}

/// Satisfies the guest's libc imports without exposing the host filesystem or network.
fn restricted_linker(engine: &Engine) -> wasmtime::Result<Linker<StoreLimits>> {
    let mut linker = Linker::new(engine);
    linker.func_wrap(WASI, "proc_exit", |_: i32| -> wasmtime::Result<()> {
        bail!("decoder exit")
    })?;
    linker.func_wrap(WASI, "fd_close", |_: i32| EBADF)?;
    linker.func_wrap(WASI, "fd_seek", |_: i32, _: i64, _: i32, _: i32| EBADF)?;
    linker.func_wrap(WASI, "fd_write", |_: i32, _: i32, _: i32, _: i32| EBADF)?;
    linker.func_wrap(WASI, "fd_read", |_: i32, _: i32, _: i32, _: i32| EBADF)?;
    linker.func_wrap(
        WASI,
        "fd_readdir",
        |_: i32, _: i32, _: i32, _: i64, _: i32| EBADF,
    )?;
    linker.func_wrap(WASI, "fd_fdstat_get", |_: i32, _: i32| EBADF)?;
    linker.func_wrap(WASI, "fd_fdstat_set_flags", |_: i32, _: i32| EBADF)?;
    linker.func_wrap(WASI, "fd_prestat_get", |_: i32, _: i32| EBADF)?;
    linker.func_wrap(WASI, "fd_prestat_dir_name", |_: i32, _: i32, _: i32| EBADF)?;
    linker.func_wrap(WASI, "clock_time_get", |_: i32, _: i64, _: i32| ENOTCAPABLE)?;
    linker.func_wrap(WASI, "poll_oneoff", |_: i32, _: i32, _: i32, _: i32| {
        ENOTCAPABLE
    })?;
    linker.func_wrap(
        WASI,
        "path_filestat_get",
        |_: i32, _: i32, _: i32, _: i32, _: i32| ENOTCAPABLE,
    )?;
    linker.func_wrap(
        WASI,
        "path_open",
        |_: i32, _: i32, _: i32, _: i32, _: i32, _: i64, _: i64, _: i32, _: i32| ENOTCAPABLE,
    )?;
    linker.func_wrap(WASI, "environ_get", |_: i32, _: i32| 0)?;
    linker.func_wrap(
        WASI,
        "environ_sizes_get",
        |mut caller: Caller<'_, StoreLimits>, count: i32, size: i32| {
            // An empty environment: no variables and no bytes.
            let Some(Extern::Memory(memory)) = caller.get_export("memory") else {
                return ENOTCAPABLE;
            };
            let mut zero = |at: i32| {
                memory
                    .write(&mut caller, at as u32 as usize, &[0; 4])
                    .is_ok()
            };
            if zero(count) && zero(size) {
                0
            } else {
                ENOTCAPABLE
            }
        },
    )?;
    Ok(linker)
}

/// Checks the pixel stream: `MAGIC, width, height, count`, then per frame a duration in
/// milliseconds and `width * height` ARGB pixels, all big-endian, with nothing after.
fn validate(bytes: &[u8]) -> wasmtime::Result<()> {
    // Every read below is in range: each check runs only after the length checks before it.
    let word = |at: usize| u32::from_be_bytes(bytes[at..at + 4].try_into().unwrap()) as usize;
    ensure!(
        (16..=MAX_OUTPUT).contains(&bytes.len()) && word(0) == MAGIC as usize,
        "invalid pixel header"
    );
    let (width, height, frames) = (word(4), word(8), word(12));
    ensure!(
        (1..=256).contains(&width)
            && (1..=256).contains(&height)
            && (1..=400).contains(&frames)
            && width * height * frames <= 12_000_000,
        "invalid pixel bounds"
    );
    let stride = 4 + 4 * width * height;
    ensure!(bytes.len() == 16 + frames * stride, "invalid pixel length");
    ensure!(
        (0..frames).all(|frame| (1..=655_350).contains(&word(16 + frame * stride))),
        "invalid pixel timing"
    );
    Ok(())
}

/// The instantiated decoder, ready to receive media.
struct Decoder {
    store: Store<StoreLimits>,
    memory: Memory,
    allocate_input: TypedFunc<i32, i32>,
    decode_media: TypedFunc<(i32, i32), i32>,
    output_ptr: TypedFunc<(), i32>,
    output_len: TypedFunc<(), i32>,
}

impl Decoder {
    fn new(engine: &Engine) -> wasmtime::Result<Self> {
        // SAFETY: `Module::deserialize` trusts its input to be Wasmtime's own compiler output.
        // `AOT` is the guest build-native.py precompiled with `config()`, embedded at build time,
        // never media; Wasmtime still rejects an artifact from another version or configuration.
        let module = unsafe { Module::deserialize(engine, AOT)? };
        let mut store = store(engine)?;
        let instance = restricted_linker(engine)?.instantiate(&mut store, &module)?;
        instance
            .get_typed_func::<(), ()>(&mut store, "_initialize")?
            .call(&mut store, ())?;
        Ok(Self {
            memory: instance
                .get_memory(&mut store, "memory")
                .context("missing memory")?,
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
        ensure!((1..=MAX_INPUT).contains(&len), "input budget");
        let ptr = self.allocate_input.call(&mut self.store, len as i32)?;
        ensure!(ptr != 0, "allocation failed");
        let start = ptr as u32 as usize;
        let target = self
            .memory
            .data_mut(&mut self.store)
            .get_mut(start..start + len)
            .context("input range")?;
        input.read_exact(target)?;
        ensure!(input.read(&mut [0; 1])? == 0, "extra input");
        Ok((ptr, len as i32))
    }

    /// Decodes received media and returns the pixel stream in guest memory.
    fn decode(&mut self, (ptr, len): (i32, i32)) -> wasmtime::Result<&[u8]> {
        ensure!(
            self.decode_media.call(&mut self.store, (ptr, len))? == 0,
            "media rejected"
        );
        let start = self.output_ptr.call(&mut self.store, ())? as u32 as usize;
        let len = self.output_len.call(&mut self.store, ())? as u32 as usize;
        self.memory
            .data(&self.store)
            .get(start..start + len)
            .context("output range")
    }
}

/// Starts the thread that interrupts guest code [`DECODE_DEADLINE`] after the returned sender
/// signals. It starts before the sandbox, which forbids creating threads, and waits for the
/// signal so the deadline does not include the caller's download.
fn deadline(engine: Engine) -> mpsc::Sender<()> {
    let (start, started) = mpsc::channel();
    std::thread::spawn(move || {
        if started.recv().is_ok() {
            std::thread::sleep(DECODE_DEADLINE);
            engine.increment_epoch();
        }
    });
    start
}

fn run() -> wasmtime::Result<()> {
    let engine = Engine::new(&seqimages_decoder::config())?;
    let mut decoder = Decoder::new(&engine)?;
    let start = deadline(engine);
    // Nothing untrusted has been read yet; everything after this line is confined.
    sandbox::restrict()?;
    let input = decoder.receive(&mut std::io::stdin().lock())?;
    // A send only fails if the thread is gone, and then nothing can expire.
    let _ = start.send(());
    let pixels = decoder.decode(input)?;
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
            // The caller may already have closed stderr; that must not turn into a panic.
            let _ = writeln!(
                std::io::stderr(),
                "Image could not be decoded within safety limits: {error}"
            );
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

    fn engine() -> Engine {
        Engine::new(&seqimages_decoder::config()).unwrap()
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

    fn instance(engine: &Engine, wat: &str) -> (Store<StoreLimits>, wasmtime::Instance) {
        let module = Module::new(engine, wat::parse_str(wat).unwrap()).unwrap();
        let mut store = store(engine).unwrap();
        let instance = wasmtime::Instance::new(&mut store, &module, &[]).unwrap();
        (store, instance)
    }

    #[test]
    fn protocol_accepts_one_frame_and_rejects_trailing_bytes() {
        assert!(validate(&[0; 16]).is_err());
        let mut bytes = Vec::new();
        for value in [MAGIC, 1, 1, 1, 20, 0xff12_3456] {
            bytes.extend(value.to_be_bytes());
        }
        assert!(validate(&bytes).is_ok());
        bytes.push(0);
        assert!(validate(&bytes).is_err());
    }

    /// Also proves the embedded artifact matches `config()`.
    #[test]
    fn embedded_decoder_streams_media_in_and_pixels_out() {
        let mut decoder = Decoder::new(&engine()).unwrap();
        let received = decoder.receive(&mut png_input(false).as_slice()).unwrap();
        let pixels = decoder.decode(received).unwrap();
        validate(pixels).unwrap();
        assert_eq!(&pixels[4..16], &[0, 0, 0, 1, 0, 0, 0, 1, 0, 0, 0, 1]);
    }

    #[test]
    fn trailing_input_is_rejected_before_decoding() {
        let mut decoder = Decoder::new(&engine()).unwrap();
        assert!(decoder.receive(&mut png_input(true).as_slice()).is_err());
    }

    #[test]
    fn infinite_loop_exhausts_fuel() {
        let engine = engine();
        let (mut store, instance) =
            instance(&engine, r#"(module (func (export "run") (loop br 0)))"#);
        store.set_fuel(1000).unwrap();
        let run = instance
            .get_typed_func::<(), ()>(&mut store, "run")
            .unwrap();
        assert!(run.call(&mut store, ()).is_err());
    }

    #[test]
    fn memory_growth_is_bounded() {
        let engine = engine();
        let wat =
            r#"(module (memory 1) (func (export "run") (result i32) i32.const 4000 memory.grow))"#;
        let (mut store, instance) = instance(&engine, wat);
        let run = instance
            .get_typed_func::<(), i32>(&mut store, "run")
            .unwrap();
        assert!(run.call(&mut store, ()).is_err());
    }

    #[test]
    fn filesystem_calls_are_inert() {
        let engine = engine();
        let mut store = store(&engine).unwrap();
        let path_open = restricted_linker(&engine)
            .unwrap()
            .get(&mut store, WASI, "path_open")
            .unwrap()
            .into_func()
            .unwrap()
            .typed::<(i32, i32, i32, i32, i32, i64, i64, i32, i32), i32>(&store)
            .unwrap();
        let result = path_open
            .call(&mut store, (3, 0, 0, 0, 0, 0, 0, 0, 0))
            .unwrap();
        assert_eq!(ENOTCAPABLE, result);
    }

    #[test]
    fn undefined_imports_such_as_sockets_cannot_instantiate() {
        let engine = engine();
        let wat = r#"(module (import "wasi_snapshot_preview1" "sock_open" (func)))"#;
        let module = Module::new(&engine, wat::parse_str(wat).unwrap()).unwrap();
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
        let engine = engine();
        let (mut store, instance) =
            instance(&engine, r#"(module (func (export "run") (loop br 0)))"#);
        store.set_fuel(u64::MAX).unwrap();
        let other = engine.clone();
        let timer = std::thread::spawn(move || {
            std::thread::sleep(Duration::from_millis(20));
            other.increment_epoch();
        });
        let run = instance
            .get_typed_func::<(), ()>(&mut store, "run")
            .unwrap();
        let error = run.call(&mut store, ()).unwrap_err();
        timer.join().unwrap();
        assert!(matches!(
            error.downcast_ref::<wasmtime::Trap>(),
            Some(wasmtime::Trap::Interrupt)
        ));
    }
}
