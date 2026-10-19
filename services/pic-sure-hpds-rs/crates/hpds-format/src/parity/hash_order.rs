//! The iteration order of a `java.util.HashSet<String>`, ported from JDK 25's `HashMap`.

use std::collections::HashSet;

const DEFAULT_INITIAL_CAPACITY: usize = 16;
const TREEIFY_THRESHOLD: usize = 8;
const UNTREEIFY_THRESHOLD: usize = 6;
const MIN_TREEIFY_CAPACITY: usize = 64;
const MAXIMUM_CAPACITY: usize = 1 << 30;

/// `String.hashCode`: `31 * h + unit` over the string's UTF-16 code units, wrapping.
pub fn java_string_hash(s: &str) -> i32 {
    s.encode_utf16().fold(0i32, |h, unit| {
        h.wrapping_mul(31).wrapping_add(i32::from(unit))
    })
}

/// `HashMap.hash` of a string: its hash code with the high half folded into the low half.
fn spread(s: &str) -> u32 {
    let h = java_string_hash(s) as u32;
    h ^ (h >> 16)
}

/// `HashMap.tableSizeFor`: the smallest power of two at least `cap`, at least 1 and at most
/// 2^30.
fn table_size_for(cap: usize) -> usize {
    cap.max(1).next_power_of_two().min(MAXIMUM_CAPACITY)
}

/// How the set's table was sized before the first insert.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum JavaHashCapacity {
    /// `new HashSet<>()` or `Collectors.toSet()`: a table of 16 allocated on the first insert.
    Default,
    /// `new HashSet<>(collection)` of `n` elements, duplicates included. JDK 25 builds it with
    /// `HashMap.newHashMap(Math.max(n, 12))`, a table of `tableSizeFor(ceil(max(n, 12) / 0.75))`.
    FromCollection(usize),
}

/// The final table of a simulated `HashSet<String>`: see [`java_hash_layout`].
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct JavaHashLayout<'a> {
    /// The table length after the last insert, 0 when nothing was inserted.
    pub capacity: usize,
    /// Each distinct string once, with its bucket, in iteration order.
    pub entries: Vec<(u32, &'a str)>,
    /// Buckets, ascending, whose order Java no longer keeps as insertion order: a bucket Java
    /// turned into a tree (9 or more entries at a capacity of 64 or more) and every bucket a
    /// tree bucket was split into on a later resize. Within these the entries are listed in
    /// insertion order, which may differ from Java's.
    pub tree_buckets: Vec<u32>,
    /// Buckets, ascending, that Java holds as trees after the last insert: a subset of
    /// [`JavaHashLayout::tree_buckets`]. A tree bucket split by a resize stays a tree in each
    /// half holding more than 6 entries and becomes a list in the others.
    pub trees: Vec<u32>,
}

/// Iteration order of a `HashSet<String>` after inserting `items` in order: each distinct
/// string once, with the table bucket it sits in. A bucket that Java turns into a tree (9 or
/// more entries at capacity 64 or more) has no emulated order within it; callers comparing
/// against Java treat the strings of one bucket as a set.
pub fn java_hash_order<'a>(
    items: impl IntoIterator<Item = &'a str>,
    capacity: JavaHashCapacity,
) -> Vec<(u32, &'a str)> {
    java_hash_layout(items, capacity).entries
}

/// [`java_hash_order`] with the final capacity and the buckets whose inner order is not
/// emulated.
///
/// It follows JDK 25's `HashMap.putVal`, `treeifyBin` and `resize`: a string already present
/// is skipped and keeps its place; a new string is appended to its bucket; if that bucket now
/// holds 9 or more entries and the table is smaller than 64, the table doubles (`treeifyBin`
/// resizes instead of treeifying); then, if the size exceeds the threshold (0.75 of the
/// table), the table doubles. A resize keeps each bucket's relative order, so the final order
/// is the buckets in ascending index and, within a bucket, insertion order.
pub fn java_hash_layout<'a>(
    items: impl IntoIterator<Item = &'a str>,
    capacity: JavaHashCapacity,
) -> JavaHashLayout<'a> {
    let mut table = Table {
        threshold: match capacity {
            JavaHashCapacity::Default => 0,
            JavaHashCapacity::FromCollection(n) => {
                let wanted = n.max(12) as f64 / 0.75;
                table_size_for(wanted.ceil() as usize)
            }
        },
        buckets: Vec::new(),
        trees: Vec::new(),
        perturbed: Vec::new(),
    };
    let mut seen: HashSet<&'a str> = HashSet::new();
    let mut size = 0usize;
    for item in items {
        if table.buckets.is_empty() {
            table.resize();
        }
        if !seen.insert(item) {
            continue;
        }
        let hash = spread(item);
        let cap = table.buckets.len();
        let index = (hash as usize) & (cap - 1);
        table.buckets[index].push((hash, item));
        if table.buckets[index].len() > TREEIFY_THRESHOLD && !table.trees[index] {
            if cap < MIN_TREEIFY_CAPACITY {
                table.resize();
            } else {
                table.trees[index] = true;
                table.perturbed[index] = true;
            }
        }
        size += 1;
        if size > table.threshold {
            table.resize();
        }
    }

    let entries = table
        .buckets
        .iter()
        .enumerate()
        .flat_map(|(index, bucket)| bucket.iter().map(move |&(_, s)| (index as u32, s)))
        .collect();
    let flagged = |flags: &[bool]| -> Vec<u32> {
        flags
            .iter()
            .enumerate()
            .filter(|&(_, &flag)| flag)
            .map(|(index, _)| index as u32)
            .collect()
    };
    JavaHashLayout {
        capacity: table.buckets.len(),
        entries,
        tree_buckets: flagged(&table.perturbed),
        trees: flagged(&table.trees),
    }
}

/// The simulated table: each bucket's entries with their spread hashes in Java's order,
/// whether Java holds the bucket as a tree, and whether its order is no longer insertion
/// order.
struct Table<'a> {
    threshold: usize,
    buckets: Vec<Vec<(u32, &'a str)>>,
    trees: Vec<bool>,
    perturbed: Vec<bool>,
}

impl Table<'_> {
    /// `HashMap.resize`: allocates the first table, or doubles it and splits every bucket
    /// into the same index and the index plus the old capacity, keeping relative order.
    fn resize(&mut self) {
        let old_cap = self.buckets.len();
        let (new_cap, new_threshold) = if old_cap > 0 {
            if old_cap >= MAXIMUM_CAPACITY {
                self.threshold = usize::MAX;
                return;
            }
            let new_cap = old_cap << 1;
            if new_cap < MAXIMUM_CAPACITY && old_cap >= DEFAULT_INITIAL_CAPACITY {
                (new_cap, self.threshold << 1)
            } else {
                (new_cap, (new_cap as f32 * 0.75) as usize)
            }
        } else if self.threshold > 0 {
            (self.threshold, (self.threshold as f32 * 0.75) as usize)
        } else {
            (DEFAULT_INITIAL_CAPACITY, 12)
        };
        let mut buckets: Vec<Vec<(u32, &str)>> = vec![Vec::new(); new_cap];
        let mut trees = vec![false; new_cap];
        let mut perturbed = vec![false; new_cap];
        for (index, bucket) in self.buckets.drain(..).enumerate() {
            for entry in bucket {
                buckets[(entry.0 as usize) & (new_cap - 1)].push(entry);
            }
            if self.perturbed[index] {
                perturbed[index] = true;
                perturbed[index + old_cap] = true;
            }
            if self.trees[index] {
                for half in [index, index + old_cap] {
                    trees[half] = buckets[half].len() > UNTREEIFY_THRESHOLD;
                }
            }
        }
        self.buckets = buckets;
        self.trees = trees;
        self.perturbed = perturbed;
        self.threshold = new_threshold;
    }
}
