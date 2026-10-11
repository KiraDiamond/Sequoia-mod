use std::path::PathBuf;
fn main() -> wasmtime::Result<()> {
    let root = PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../wasm");
    let bytes = std::fs::read(root.join("decoder.wasm"))?;
    let host_os = match std::env::consts::OS {
        "windows" => "windows",
        "linux" => "linux",
        "macos" => "macos",
        _ => panic!("unsupported OS"),
    };
    let arch = std::env::consts::ARCH;
    let wanted = std::env::args()
        .nth(1)
        .unwrap_or_else(|| format!("{host_os}-{arch}"));
    let triple = match wanted.as_str() {
        "windows-x86_64" => "x86_64-pc-windows-msvc",
        "windows-aarch64" => "aarch64-pc-windows-msvc",
        "linux-x86_64" => "x86_64-unknown-linux-musl",
        "linux-aarch64" => "aarch64-unknown-linux-musl",
        "macos-x86_64" => "x86_64-apple-darwin",
        "macos-aarch64" => "aarch64-apple-darwin",
        _ => panic!("unsupported platform"),
    };
    let mut c = wasmtime::Config::new();
    c.consume_fuel(true)
        .epoch_interruption(true)
        .max_wasm_stack(512 * 1024);
    c.wasm_memory64(false)
        .wasm_multi_memory(false)
        .target(triple)?;
    let e = wasmtime::Engine::new(&c)?;
    let compiled = e.precompile_module(&bytes)?;
    std::fs::write(root.join(format!("{wanted}.cwasm")), &compiled)?;
    println!("{wanted} precompiled bytes={}", compiled.len());
    Ok(())
}
