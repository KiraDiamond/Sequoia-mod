//! Wasmtime settings shared by the decoder and `examples/precompile.rs`.
//!
//! Precompiled modules only load into an engine with compatible settings, so both sides build
//! their configuration here; `Module::deserialize` rejects an artifact compiled differently.

use wasmtime::{Config, Engine};

/// Guest stack limit in bytes.
pub const MAX_WASM_STACK: usize = 512 * 1024;

/// Returns the configuration every decoder engine and precompiled artifact must use.
pub fn config() -> Config {
    let mut config = Config::new();
    config
        .consume_fuel(true)
        .epoch_interruption(true)
        .max_wasm_stack(MAX_WASM_STACK)
        .wasm_memory64(false)
        .wasm_multi_memory(false);
    config
}

/// Creates a host engine with the shared configuration.
///
/// # Errors
///
/// Returns an error if Wasmtime rejects the configuration on this host.
pub fn engine() -> wasmtime::Result<Engine> {
    Engine::new(&config())
}
