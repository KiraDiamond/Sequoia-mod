//! Compiles the freshly built decoder for one platform with the runtime's exact settings.

use std::path::PathBuf;

fn main() -> wasmtime::Result<()> {
    let build = PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../../build");
    let root = build.join("native-aot");
    let bytes = std::fs::read(build.join("native-wasm/decoder.wasm"))?;
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
    std::fs::create_dir_all(&root)?;
    let compiled = wasmtime::Engine::new(&config)?.precompile_module(&bytes)?;
    std::fs::write(root.join(format!("{wanted}.cwasm")), &compiled)?;
    println!("{wanted} precompiled bytes={}", compiled.len());
    Ok(())
}
