mod os;
use std::io::{Read, Write};
use std::sync::{
    Arc,
    atomic::{AtomicBool, Ordering},
};
use wasmtime::{Config, Engine, Linker, Module, Store, StoreLimits, StoreLimitsBuilder};
const MAX_INPUT: usize = 8 * 1024 * 1024;
const MAX_OUTPUT: usize = 48_001_616;
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
fn engine() -> wasmtime::Result<Engine> {
    let mut c = Config::new();
    c.consume_fuel(true)
        .epoch_interruption(true)
        .max_wasm_stack(512 * 1024);
    c.wasm_memory64(false).wasm_multi_memory(false);
    Engine::new(&c)
}
fn store(engine: &Engine) -> wasmtime::Result<Store<State>> {
    let mut s = Store::new(
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
    s.limiter(|s| &mut s.limits);
    s.set_fuel(10_000_000_000)?;
    s.set_epoch_deadline(1);
    Ok(s)
}
fn restricted_linker(e: &Engine) -> wasmtime::Result<Linker<State>> {
    let mut linker = Linker::new(e);
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
            let Some(mem) = caller.get_export("memory").and_then(|e| e.into_memory()) else {
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
fn validate(bytes: &[u8]) -> Result<(), String> {
    fn value(b: &[u8], at: usize) -> Result<u32, String> {
        Ok(u32::from_be_bytes(
            b.get(at..at + 4)
                .ok_or("truncated pixels")?
                .try_into()
                .unwrap(),
        ))
    }
    if bytes.len() < 16 || bytes.len() > MAX_OUTPUT || value(bytes, 0)? != 0x53494d31 {
        return Err("invalid pixel header".into());
    }
    let (w, h, n) = (
        value(bytes, 4)? as usize,
        value(bytes, 8)? as usize,
        value(bytes, 12)? as usize,
    );
    if w == 0 || h == 0 || w > 256 || h > 256 || n == 0 || n > 400 || w * h * n > 12_000_000 {
        return Err("invalid pixel bounds".into());
    }
    let stride = 4 + 4 * w * h;
    if bytes.len() != 16 + n * stride {
        return Err("invalid pixel length".into());
    }
    for i in 0..n {
        let ms = value(bytes, 16 + i * stride)?;
        if ms == 0 || ms > 655350 {
            return Err("invalid pixel timing".into());
        }
    }
    Ok(())
}
fn decode(input: &[u8]) -> wasmtime::Result<Vec<u8>> {
    if input.is_empty() || input.len() > MAX_INPUT {
        return Err(wasmtime::Error::msg("input budget"));
    }
    let e = engine()?;
    // SAFETY: AOT is immutable, bundled output from the fixed decoder.wasm, compiled
    // with the same Wasmtime version and engine settings. Never deserialize downloaded bytes.
    let m = unsafe { Module::deserialize(&e, AOT) }?;
    let mut s = store(&e)?;
    let linker = restricted_linker(&e)?;
    let instance = linker.instantiate(&mut s, &m)?;
    let mem = instance
        .get_memory(&mut s, "memory")
        .ok_or_else(|| wasmtime::Error::msg("missing memory"))?;
    let alloc = instance.get_typed_func::<i32, i32>(&mut s, "allocate_input")?;
    let run = instance.get_typed_func::<(i32, i32), i32>(&mut s, "decode_media")?;
    let output_ptr = instance.get_typed_func::<(), i32>(&mut s, "output_ptr")?;
    let output_len = instance.get_typed_func::<(), i32>(&mut s, "output_len")?;
    let finished = Arc::new(AtomicBool::new(false));
    let done = finished.clone();
    let ticker = std::thread::spawn(move || {
        for _ in 0..1500 {
            if done.load(Ordering::Relaxed) {
                return;
            }
            std::thread::sleep(std::time::Duration::from_millis(10));
        }
        e.increment_epoch();
    });
    os::restrict()?;
    let result = (|| -> wasmtime::Result<Vec<u8>> {
        if let Some(init) = instance.get_func(&mut s, "_initialize") {
            init.typed::<(), ()>(&s)?.call(&mut s, ())?;
        }
        let ptr = alloc.call(&mut s, input.len() as i32)? as u32 as usize;
        if ptr == 0 {
            return Err(wasmtime::Error::msg("allocation failed"));
        }
        mem.write(&mut s, ptr, input)?;
        if run.call(&mut s, (ptr as i32, input.len() as i32))? != 0 {
            return Err(wasmtime::Error::msg("media rejected"));
        }
        let ptr = output_ptr.call(&mut s, ())? as u32 as usize;
        let len = output_len.call(&mut s, ())? as u32 as usize;
        if !(16..=MAX_OUTPUT).contains(&len) {
            return Err(wasmtime::Error::msg("output budget"));
        }
        let end = ptr
            .checked_add(len)
            .ok_or_else(|| wasmtime::Error::msg("output overflow"))?;
        let bytes = mem
            .data(&s)
            .get(ptr..end)
            .ok_or_else(|| wasmtime::Error::msg("output range"))?
            .to_vec();
        validate(&bytes).map_err(wasmtime::Error::msg)?;
        Ok(bytes)
    })();
    finished.store(true, Ordering::Relaxed);
    let _ = ticker.join();
    result
}
fn main() {
    let result = (|| -> Result<(), Box<dyn std::error::Error>> {
        let mut stream = std::io::stdin().lock();
        let mut header = [0; 4];
        stream.read_exact(&mut header)?;
        let len = u32::from_be_bytes(header) as usize;
        if len == 0 || len > MAX_INPUT {
            return Err("input budget".into());
        }
        let mut bytes = vec![0; len];
        stream.read_exact(&mut bytes)?;
        let mut extra = [0; 1];
        if stream.read(&mut extra)? != 0 {
            return Err("extra input".into());
        }
        let pixels = decode(&bytes)?;
        let mut out = std::io::stdout().lock();
        out.write_all(&pixels)?;
        out.flush()?;
        Ok(())
    })();
    if let Err(e) = result {
        eprintln!("Image could not be decoded within safety limits: {e}");
        std::process::exit(1)
    }
}
#[cfg(test)]
mod tests {
    use super::*;
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
    fn infinite_loop_exhausts_fuel() {
        let e = engine().unwrap();
        let m = Module::new(
            &e,
            wat::parse_str("(module (func (export \"run\") (loop br 0)))").unwrap(),
        )
        .unwrap();
        let mut s = store(&e).unwrap();
        s.set_fuel(1000).unwrap();
        let i = wasmtime::Instance::new(&mut s, &m, &[]).unwrap();
        assert!(
            i.get_typed_func::<(), ()>(&mut s, "run")
                .unwrap()
                .call(&mut s, ())
                .is_err()
        );
    }
    #[test]
    fn memory_growth_is_bounded() {
        let e = engine().unwrap();
        let m=Module::new(&e,wat::parse_str("(module (memory 1) (func (export \"run\") (result i32) i32.const 4000 memory.grow))").unwrap()).unwrap();
        let mut s = store(&e).unwrap();
        let i = wasmtime::Instance::new(&mut s, &m, &[]).unwrap();
        assert!(
            i.get_typed_func::<(), i32>(&mut s, "run")
                .unwrap()
                .call(&mut s, ())
                .is_err()
        );
    }
    #[test]
    fn filesystem_import_cannot_instantiate() {
        let e = engine().unwrap();
        let m = Module::new(
            &e,
            wat::parse_str("(module (import \"wasi_snapshot_preview1\" \"path_open\" (func)))")
                .unwrap(),
        )
        .unwrap();
        let mut s = store(&e).unwrap();
        assert!(
            restricted_linker(&e)
                .unwrap()
                .instantiate(&mut s, &m)
                .is_err()
        );
    }

    #[test]
    fn filesystem_calls_are_inert() {
        let e = engine().unwrap();
        let mut s = store(&e).unwrap();
        let l = restricted_linker(&e).unwrap();
        let f = l
            .get(&mut s, "wasi_snapshot_preview1", "path_open")
            .unwrap()
            .into_func()
            .unwrap();
        let f = f
            .typed::<(i32, i32, i32, i32, i32, i64, i64, i32, i32), i32>(&s)
            .unwrap();
        assert_eq!(76, f.call(&mut s, (3, 0, 0, 0, 0, 0, 0, 0, 0)).unwrap());
    }
    #[test]
    fn network_import_is_unavailable() {
        let e = engine().unwrap();
        let m = Module::new(
            &e,
            wat::parse_str("(module (import \"wasi_snapshot_preview1\" \"sock_open\" (func)))")
                .unwrap(),
        )
        .unwrap();
        let mut s = store(&e).unwrap();
        assert!(
            restricted_linker(&e)
                .unwrap()
                .instantiate(&mut s, &m)
                .is_err()
        );
    }
    #[test]
    fn external_deadline_interrupts_a_loop() {
        let e = engine().unwrap();
        let m = Module::new(
            &e,
            wat::parse_str("(module (func (export \"run\") (loop br 0)))").unwrap(),
        )
        .unwrap();
        let mut s = store(&e).unwrap();
        s.set_fuel(u64::MAX).unwrap();
        let i = wasmtime::Instance::new(&mut s, &m, &[]).unwrap();
        let other = e.clone();
        let timer = std::thread::spawn(move || {
            std::thread::sleep(std::time::Duration::from_millis(20));
            other.increment_epoch();
        });
        let error = i
            .get_typed_func::<(), ()>(&mut s, "run")
            .unwrap()
            .call(&mut s, ())
            .unwrap_err();
        timer.join().unwrap();
        assert!(matches!(
            error.downcast_ref::<wasmtime::Trap>(),
            Some(wasmtime::Trap::Interrupt)
        ));
    }
}
