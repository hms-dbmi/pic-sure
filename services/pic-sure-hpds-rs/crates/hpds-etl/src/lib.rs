//! Loading and migration pipeline that produces the phenotype store.
//!
//! [`load`] turns phenotype CSV into a store, reproducing the Java loaders' rules, and
//! [`migrate`] turns a javabin store exported by the Java `ExportForMigration` loader into
//! one. A consent-partitioned load or migration builds each partition on its own and
//! [`merge`] writes them into one store whose blocks hold every partition's rows;
//! [`convert`] does the same from a store of format 1 (Rust HPDS v1 to v3). [`dump`] writes a
//! store as canonical CSV, and [`store_maintenance`] removes concepts and regenerates
//! `columnMeta.csv`. [`java`] holds the JDK and library behavior those rules depend on,
//! [`column_meta_csv`] writes `columnMeta.csv` byte for byte as Java does, and [`store`] opens
//! existing stores and holds their lock and `.generation` marker. [`partitions`] holds the
//! partition directories of exports and of format 1 stores. [`output`] writes the files
//! `dump` produces outside a store. [`publish`] replaces a store's files in place through a
//! journal that `recover` finishes after a crash, and [`maintenance_log`] keeps the record
//! of every change made to a store in place. [`cleanup`] removes the temporary directories of an
//! interrupted run.

pub mod cleanup;
pub mod column_meta_csv;
pub mod convert;
pub mod dump;
mod error;
pub mod java;
pub mod load;
pub mod maintenance_log;
pub mod merge;
pub mod migrate;
pub mod output;
pub mod partitions;
mod private_fs;
pub mod publish;
pub mod store;
pub mod store_maintenance;

pub use error::{EtlError, Result};

/// Returns the banner the `hpds-etl` binary prints on startup, also recorded as the ETL
/// version in every store's index header.
pub fn banner() -> String {
    format!("hpds-etl {}", env!("CARGO_PKG_VERSION"))
}

#[cfg(test)]
mod tests {
    #[test]
    fn banner_names_the_tool() {
        assert!(super::banner().starts_with("hpds-etl "));
    }
}
