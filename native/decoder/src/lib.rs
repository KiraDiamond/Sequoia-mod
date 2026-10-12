//! Shared pieces of the Sequoia Images decoder helper: the Wasmtime configuration used both at
//! runtime and for ahead-of-time compilation, and the OS sandbox applied before media is read.

pub mod engine;
pub mod sandbox;
