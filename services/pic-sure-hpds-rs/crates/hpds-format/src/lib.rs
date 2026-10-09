//! On-disk format for the Rust HPDS phenotype store.

/// Name of the index file that marks a populated phenotype data directory.
pub const INDEX_FILE_NAME: &str = "pheno.index";

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn index_file_name_is_stable() {
        assert_eq!(INDEX_FILE_NAME, "pheno.index");
    }
}
