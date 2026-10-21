//! INFO_COLUMN_LISTING: `getInfoColumnMeta` as the store's layout combines it.
//!
//! A node lists each of its INFO columns once. `GenomicProcessorParentImpl` concatenates its
//! nodes' lists, so in `localDistributed` a column appears once per chromosome node.
//! `GenomicProcessorPatientMergingParentImpl` puts each child's list into a `HashSet` before
//! concatenating, so in `localPatientDistributed` a column appears once per group (once per
//! distinct description and kind within the group), and in `localPatientOnlyDistributed` once
//! per group node. `min` and `max` are never assigned in Java, so every entry carries
//! `Float.MAX_VALUE` and `Float.MIN_VALUE`. Java's order within a node or group is a hash
//! order; Rust lists nodes and groups in index order and columns in column-table order.

use hpds_format::GenoMode;

use super::store::GenomicStore;

/// `Float.MAX_VALUE` as Jackson writes it, every entry's `min`.
const UNSET_MIN: &str = "3.4028235E38";

/// `Float.MIN_VALUE` as Jackson writes it, every entry's `max`.
const UNSET_MAX: &str = "1.4E-45";

/// One entry of INFO_COLUMN_LISTING, Java's `InfoColumnMeta` less its unassigned bounds.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct InfoColumnMeta {
    /// The column, the info file key Java uses as its name in queries.
    pub key: String,
    /// The ETL's description, `Description="..."` as stored.
    pub description: String,
    /// Whether the node holds the column as numeric.
    pub continuous: bool,
}

/// The listing of `store`'s INFO columns (see the module docs).
pub(crate) fn info_column_listing(store: &GenomicStore) -> Vec<InfoColumnMeta> {
    let index = store.index();
    let columns = index.columns();
    let entry = |e: &hpds_format::GenoInfoEntry| InfoColumnMeta {
        key: columns[usize::from(e.column)].clone(),
        description: e.description.clone(),
        continuous: e.continuous,
    };
    let nodes = index.nodes();
    if store.mode() != GenoMode::LocalPatientDistributed {
        return nodes
            .iter()
            .flat_map(|node| node.infos.iter().map(entry))
            .collect();
    }
    let mut listing = Vec::new();
    for g in 0..index.groups().len() {
        let mut group: Vec<InfoColumnMeta> = Vec::new();
        for node in nodes.iter().filter(|node| usize::from(node.group) == g) {
            for e in &node.infos {
                let meta = entry(e);
                if !group.contains(&meta) {
                    group.push(meta);
                }
            }
        }
        listing.extend(group);
    }
    listing
}

/// The listing as Jackson writes `List<InfoColumnMeta>`: a JSON array of
/// `{key, description, continuous, min, max}`.
pub(crate) fn info_column_listing_json(listing: &[InfoColumnMeta]) -> String {
    let mut out = String::from("[");
    for (i, meta) in listing.iter().enumerate() {
        if i > 0 {
            out.push(',');
        }
        let text = |s: &str| serde_json::to_string(s).unwrap_or_default();
        out.push_str(&format!(
            "{{\"key\":{},\"description\":{},\"continuous\":{},\"min\":{UNSET_MIN},\"max\":{UNSET_MAX}}}",
            text(&meta.key),
            text(&meta.description),
            meta.continuous
        ));
    }
    out.push(']');
    out
}
