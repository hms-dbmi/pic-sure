//! Query corpus generation, response comparison and load replay for HPDS v3 services.
//!
//! The crate drives any server that speaks the `PIC-SURE/v3` HTTP contract, so the same corpus
//! runs against the Java service and the Rust one.

pub mod client;
pub mod column_meta;
pub mod compare;
pub mod corpus;
pub mod duration;
pub mod error;
pub mod golden;
pub mod load;
pub mod rng;

pub use error::{BenchError, Result};
