//! Which consent partition a patient's rows belong to, from a store's partition table.

use roaring::RoaringBitmap;

/// Patient ids above this many times the patient count, plus [`DENSE_SLACK`], are routed
/// through a sorted list instead of a table indexed by patient id.
const DENSE_FACTOR: u64 = 4;

/// Slack added to the dense bound, so small stores always get the table.
const DENSE_SLACK: u64 = 1 << 20;

/// The partition of each patient of a partitioned store whose partition the patient alone
/// decides: every patient in exactly one partition's patient set and not in the overlap
/// set `O`. Patients in `O`, and patients in no partition, route nowhere; their rows are
/// read by tag.
///
/// It may also carry, per partition, the overlap patients with a row tagged with it
/// ([`Membership::with_tagged`]).
///
/// Dense ids get a table of one byte per patient id up to the largest (two bytes past 254
/// partitions); sparse ids a sorted list searched by bisection.
#[derive(Clone, Debug, Default)]
pub struct Membership {
    route: Route,
    partition_count: usize,
    tagged: Option<Vec<RoaringBitmap>>,
}

#[derive(Clone, Debug, Default)]
enum Route {
    #[default]
    Unpartitioned,
    Narrow(Vec<u8>),
    Wide(Vec<u16>),
    Sparse(Vec<(u32, u16)>),
}

const NARROW_NONE: u8 = u8::MAX;
const WIDE_NONE: u16 = u16::MAX;

impl Membership {
    /// The membership of an unpartitioned store: no partition, every patient routes
    /// nowhere.
    pub fn unpartitioned() -> Self {
        Membership::default()
    }

    /// The membership of a store whose partition `i` holds the patients `partitions[i]`,
    /// with overlap set `overlap`. A patient routes to `i` when `partitions[i]` is the only
    /// set holding them and they are not in `overlap`.
    pub fn new(partitions: &[&RoaringBitmap], overlap: &RoaringBitmap) -> Self {
        let partition_count = partitions.len();
        let mut count = 0u64;
        let mut max = None::<u32>;
        for set in partitions {
            count += set.len();
            max = max.max(set.max());
        }
        let dense = max.is_some_and(|max| u64::from(max) < DENSE_FACTOR * count + DENSE_SLACK);
        let route = if partition_count < usize::from(NARROW_NONE) && dense {
            let mut table = vec![NARROW_NONE; max.map_or(0, |m| m as usize + 1)];
            fill_dense(&mut table, partitions, overlap, NARROW_NONE, |i| i as u8);
            Route::Narrow(table)
        } else if dense {
            let mut table = vec![WIDE_NONE; max.map_or(0, |m| m as usize + 1)];
            fill_dense(&mut table, partitions, overlap, WIDE_NONE, |i| i as u16);
            Route::Wide(table)
        } else {
            let mut pairs: Vec<(u32, u16)> = Vec::with_capacity(count as usize);
            for (i, set) in partitions.iter().enumerate() {
                pairs.extend(set.iter().map(|p| (p, i as u16)));
            }
            pairs.sort_unstable();
            let mut routed: Vec<(u32, u16)> = Vec::with_capacity(pairs.len());
            let mut cursor = 0;
            while cursor < pairs.len() {
                let patient = pairs[cursor].0;
                let end = cursor
                    + pairs[cursor..]
                        .iter()
                        .take_while(|p| p.0 == patient)
                        .count();
                if end - cursor == 1 && !overlap.contains(patient) {
                    routed.push(pairs[cursor]);
                }
                cursor = end;
            }
            Route::Sparse(routed)
        };
        Membership {
            route,
            partition_count,
            tagged: None,
        }
    }

    /// This membership with `tagged[i]` as the overlap patients whose rows are tagged with
    /// partition `i` ([`crate::IndexPartition::tagged`]), which [`Membership::tagged_with`]
    /// then answers from.
    #[must_use]
    pub fn with_tagged(mut self, tagged: Vec<RoaringBitmap>) -> Self {
        self.tagged = Some(tagged);
        self
    }

    /// Whether the store records `patient` as having rows tagged with `partition`: `None`
    /// when this membership carries no tagged sets.
    pub fn tagged_with(&self, partition: u16, patient: u32) -> Option<bool> {
        let tagged = self.tagged.as_ref()?;
        Some(
            tagged
                .get(usize::from(partition))
                .is_some_and(|set| set.contains(patient)),
        )
    }

    /// The number of partitions in the store, 0 when unpartitioned.
    pub fn partition_count(&self) -> usize {
        self.partition_count
    }

    /// The partition `patient`'s rows belong to, or `None` when the patient alone does not
    /// decide it (a patient in the overlap set, or in no partition) or the store is
    /// unpartitioned.
    pub fn partition_of(&self, patient: u32) -> Option<u16> {
        match &self.route {
            Route::Unpartitioned => None,
            Route::Narrow(table) => table
                .get(patient as usize)
                .filter(|&&p| p != NARROW_NONE)
                .map(|&p| u16::from(p)),
            Route::Wide(table) => table
                .get(patient as usize)
                .copied()
                .filter(|&p| p != WIDE_NONE),
            Route::Sparse(pairs) => pairs
                .binary_search_by_key(&patient, |p| p.0)
                .ok()
                .map(|i| pairs[i].1),
        }
    }

    /// Approximate heap bytes held.
    pub fn heap_size(&self) -> usize {
        match &self.route {
            Route::Unpartitioned => 0,
            Route::Narrow(table) => table.capacity(),
            Route::Wide(table) => table.capacity() * 2,
            Route::Sparse(pairs) => pairs.capacity() * 8,
        }
    }
}

/// Fills a dense route table: each patient of exactly one set and not in `overlap` gets
/// that set's index, every other patient `none`.
fn fill_dense<T: Copy + PartialEq>(
    table: &mut [T],
    partitions: &[&RoaringBitmap],
    overlap: &RoaringBitmap,
    none: T,
    index: impl Fn(usize) -> T,
) {
    let mut seen = RoaringBitmap::new();
    let mut shared = RoaringBitmap::new();
    for (i, set) in partitions.iter().enumerate() {
        shared |= &seen & *set;
        seen |= *set;
        for patient in set.iter() {
            table[patient as usize] = index(i);
        }
    }
    for patient in shared.iter().chain(overlap.iter()) {
        if let Some(slot) = table.get_mut(patient as usize) {
            *slot = none;
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn sets(lists: &[&[u32]]) -> Vec<RoaringBitmap> {
        lists.iter().map(|l| l.iter().copied().collect()).collect()
    }

    #[test]
    fn routes_patients_of_one_partition_outside_the_overlap() {
        let sets = sets(&[&[1, 2, 3], &[3, 4], &[5]]);
        let refs: Vec<&RoaringBitmap> = sets.iter().collect();
        let overlap: RoaringBitmap = [3, 5].into_iter().collect();
        let dense = Membership::new(&refs, &overlap);
        assert!(matches!(dense.route, Route::Narrow(_)));
        let routes: Vec<Option<u16>> = (0..7).map(|p| dense.partition_of(p)).collect();
        assert_eq!(routes, [None, Some(0), Some(0), None, Some(1), None, None]);
        let sparse_sets = sets
            .iter()
            .map(|s| {
                s.iter()
                    .map(|p| p + 4_000_000_000)
                    .collect::<RoaringBitmap>()
            })
            .collect::<Vec<_>>();
        let sparse_refs: Vec<&RoaringBitmap> = sparse_sets.iter().collect();
        let sparse_overlap: RoaringBitmap = overlap.iter().map(|p| p + 4_000_000_000).collect();
        let sparse = Membership::new(&sparse_refs, &sparse_overlap);
        assert!(matches!(sparse.route, Route::Sparse(_)));
        let routes: Vec<Option<u16>> = (0..7)
            .map(|p| sparse.partition_of(p + 4_000_000_000))
            .collect();
        assert_eq!(routes, [None, Some(0), Some(0), None, Some(1), None, None]);
        assert_eq!(Membership::unpartitioned().partition_of(1), None);
    }

    #[test]
    fn many_partitions_take_two_byte_routes() {
        let sets: Vec<RoaringBitmap> = (0..300u32).map(|i| [i].into_iter().collect()).collect();
        let refs: Vec<&RoaringBitmap> = sets.iter().collect();
        let membership = Membership::new(&refs, &RoaringBitmap::new());
        assert!(matches!(membership.route, Route::Wide(_)));
        assert_eq!(membership.partition_of(299), Some(299));
        assert_eq!(membership.partition_count(), 300);
    }
}
