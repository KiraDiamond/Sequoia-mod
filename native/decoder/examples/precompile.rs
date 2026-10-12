//! Compiles the fixed `native/wasm/decoder.wasm` for one platform with the runtime's exact
//! engine configuration, writing `native/wasm/<platform>.cwasm`.

use std::path::PathBuf;

fn main() -> wasmtime::Result<()> {
    let root = PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../wasm");
    let bytes = std::fs::read(root.join("decoder.wasm"))?;
    let host = format!("{}-{}", std::env::consts::OS, std::env::consts::ARCH);
    let wanted = std::env::args().nth(1).unwrap_or(host);
    let triple = match wanted.as_str() {
        "windows-x86_64" => "x86_64-pc-windows-msvc",
        "windows-aarch64" => "aarch64-pc-windows-msvc",
        "linux-x86_64" => "x86_64-unknown-linux-musl",
        "linux-aarch64" => "aarch64-unknown-linux-musl",
        "macos-x86_64" => "x86_64-apple-darwin",
        "macos-aarch64" => "aarch64-apple-darwin",
        other => {
            return Err(wasmtime::Error::msg(format!(
                "unsupported platform {other}"
            )));
        }
    };
    let mut config = seqimages_decoder::engine::config();
    config.target(triple)?;
    let compiled = wasmtime::Engine::new(&config)?.precompile_module(&bytes)?;
    std::fs::write(root.join(format!("{wanted}.cwasm")), &compiled)?;
    println!("{wanted} precompiled bytes={}", compiled.len());
    Ok(())
}
