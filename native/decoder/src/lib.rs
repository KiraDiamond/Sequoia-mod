//! Shared pieces of the Sequoia Images decoder helper: the OS sandbox applied before media is
//! read, and the Wasmtime configuration used both at runtime and for ahead-of-time compilation.

pub mod sandbox;

/// The configuration every decoder engine and precompiled artifact must use;
/// `Module::deserialize` rejects an artifact compiled with different settings.
pub fn config() -> wasmtime::Config {
    let mut config = wasmtime::Config::new();
    config.consume_fuel(true).epoch_interruption(true);
    config
}
