//! Loading and migration pipeline that produces the phenotype store.

/// Returns the banner the `hpds-etl` binary prints on startup.
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
