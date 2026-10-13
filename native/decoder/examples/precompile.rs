//! Compiles the decoder guest for one target with the runtime's exact engine configuration.
//!
//! usage: `precompile TARGET_TRIPLE GUEST.wasm OUTPUT.cwasm`

fn main() -> wasmtime::Result<()> {
    let args: Vec<String> = std::env::args().skip(1).collect();
    let [triple, guest, output] = args.as_slice() else {
        wasmtime::bail!("usage: precompile TARGET_TRIPLE GUEST.wasm OUTPUT.cwasm");
    };
    let mut config = seqimages_decoder::config();
    config.target(triple)?;
    let compiled = wasmtime::Engine::new(&config)?.precompile_module(&std::fs::read(guest)?)?;
    std::fs::write(output, &compiled)?;
    println!("{triple} precompiled bytes={}", compiled.len());
    Ok(())
}
