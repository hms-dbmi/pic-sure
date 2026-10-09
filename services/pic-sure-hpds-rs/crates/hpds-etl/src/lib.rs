//! Loading and migration pipeline that produces the phenotype store.
//!
//! [`load`] turns phenotype CSV into a store, reproducing the Java loaders' rules.
//! [`java`] holds the JDK and library behavior those rules depend on, and
//! [`column_meta_csv`] writes `columnMeta.csv` byte for byte as Java does.

pub mod column_meta_csv;
mod error;
pub mod java;
pub mod load;

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
