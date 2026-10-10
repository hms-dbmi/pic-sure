//! Partition views: one concept's rows grouped by the consent partition they belong to,
//! derived in memory from the block, so a selection of few partitions does work in
//! proportion to their rows instead of the whole block's.
//!
//! A row belongs to its tag's partition in a tagged block, and otherwise to the one
//! partition its patient routes to ([`Membership::partition_of`]). The block's read checks
//! have already confirmed that every row belongs to one of its holders.

use std::collections::HashMap;
use std::ops::Range;

use hpds_format::{Block, CategoricalBlock, Membership, NumericBlock, PartitionHolder};
use roaring::RoaringBitmap;

use crate::error::{QueryError, Result};

/// One concept's rows split by holder. A slot is a holder's position in the block's holder
/// list, so slots ascend with partitions.
#[derive(Debug)]
pub(crate) struct View {
    partitions: Box<[u16]>,
    patients: Vec<RoaringBitmap>,
    ends: Vec<u32>,
    positions: Vec<u32>,
    value_ends: Vec<u32>,
    parts: Vec<(u16, RoaringBitmap)>,
}

impl View {
    /// The view of `block`, whose holders are `holders`, under the store's `membership`.
    /// `path` names the concept in errors.
    ///
    /// For every slot it holds the patients with a row in that partition. For a numeric
    /// block it also holds the value positions of each slot's rows, ascending, so each
    /// group is in value order. For a categorical block it holds, per dictionary value and
    /// slot, the patients holding that value in that partition, for the slots where there
    /// are any.
    ///
    /// # Errors
    /// [`QueryError::StoreInconsistent`] when a row belongs to no holder, which a block that
    /// passed its read checks never does.
    pub(crate) fn build(
        block: &Block,
        holders: &[PartitionHolder],
        membership: &Membership,
        path: &str,
    ) -> Result<Self> {
        let router = Router::new(holders, membership, block.partitions().is_tagged(), path);
        let partitions: Box<[u16]> = holders.iter().map(|h| h.partition).collect();
        match block {
            Block::Numeric(b) => numeric_view(b, &router, partitions),
            Block::Categorical(b) => categorical_view(b, &router, partitions),
        }
    }

    /// The slots whose partition `mask` selects, ascending.
    pub(crate) fn selected_slots<'m>(
        &'m self,
        mask: &'m [bool],
    ) -> impl Iterator<Item = usize> + Clone + 'm {
        self.partitions
            .iter()
            .enumerate()
            .filter(|(_, p)| mask.get(usize::from(**p)).copied().unwrap_or(false))
            .map(|(slot, _)| slot)
    }

    /// The partition of slot `slot`.
    pub(crate) fn partition(&self, slot: usize) -> u16 {
        self.partitions[slot]
    }

    /// The patients with a row in slot `slot`'s partition.
    pub(crate) fn patients(&self, slot: usize) -> &RoaringBitmap {
        &self.patients[slot]
    }

    /// The value positions of slot `slot`'s rows of a numeric block, ascending. Empty for a
    /// categorical block.
    pub(crate) fn group(&self, slot: usize) -> &[u32] {
        if self.ends.is_empty() {
            return &[];
        }
        let start = slot.checked_sub(1).map_or(0, |s| self.ends[s] as usize);
        &self.positions[start..self.ends[slot] as usize]
    }

    /// The range in the part list of dictionary value `value`'s parts.
    pub(crate) fn parts_of(&self, value: usize) -> Range<usize> {
        let start = value
            .checked_sub(1)
            .map_or(0, |v| self.value_ends[v] as usize);
        start..self.value_ends[value] as usize
    }

    /// The slot and patients of part `index`.
    pub(crate) fn part(&self, index: usize) -> (usize, &RoaringBitmap) {
        let (slot, patients) = &self.parts[index];
        (usize::from(*slot), patients)
    }

    /// Approximate heap bytes held, for the cache weight.
    pub(crate) fn heap_size(&self) -> usize {
        let bitmap = |b: &RoaringBitmap| b.serialized_size() + size_of::<RoaringBitmap>();
        self.partitions.len() * 2
            + self.patients.iter().map(bitmap).sum::<usize>()
            + (self.ends.capacity() + self.positions.capacity() + self.value_ends.capacity()) * 4
            + self.parts.iter().map(|(_, b)| 8 + bitmap(b)).sum::<usize>()
    }
}

/// The slot each row of one block belongs to.
struct Router<'a> {
    slot_of: Vec<u16>,
    membership: &'a Membership,
    tagged: bool,
    path: &'a str,
}

const NO_SLOT: u16 = u16::MAX;

impl<'a> Router<'a> {
    fn new(
        holders: &[PartitionHolder],
        membership: &'a Membership,
        tagged: bool,
        path: &'a str,
    ) -> Self {
        let mut slot_of = vec![NO_SLOT; membership.partition_count()];
        for (slot, holder) in holders.iter().enumerate() {
            if let Some(entry) = slot_of.get_mut(usize::from(holder.partition)) {
                *entry = slot as u16;
            }
        }
        Router {
            slot_of,
            membership,
            tagged,
            path,
        }
    }

    /// The slot of a row of `patient` whose tag is `tag`.
    fn slot(&self, patient: u32, tag: Option<u16>) -> Result<u16> {
        let partition = if self.tagged {
            tag
        } else {
            self.membership.partition_of(patient)
        };
        partition
            .and_then(|p| self.slot_of.get(usize::from(p)).copied())
            .filter(|&slot| slot != NO_SLOT)
            .ok_or_else(|| QueryError::StoreInconsistent {
                concept_path: self.path.to_owned(),
                detail: "a row belongs to no partition holding the concept",
            })
    }
}

fn numeric_view(b: &NumericBlock, router: &Router<'_>, partitions: Box<[u16]>) -> Result<View> {
    let h = partitions.len();
    let patients = b.patients();
    let mut slot_by_pos: Vec<u16> = Vec::with_capacity(patients.len());
    let mut counts = vec![0u32; h];
    for (pos, &patient) in patients.iter().enumerate() {
        let slot = router.slot(patient, b.tag_at_value_pos(pos))?;
        counts[usize::from(slot)] += 1;
        slot_by_pos.push(slot);
    }
    let mut ends = Vec::with_capacity(h);
    let mut next = Vec::with_capacity(h);
    let mut total = 0u32;
    for &count in &counts {
        next.push(total);
        total += count;
        ends.push(total);
    }
    let mut positions = vec![0u32; patients.len()];
    for (pos, &slot) in slot_by_pos.iter().enumerate() {
        let cursor = &mut next[usize::from(slot)];
        positions[*cursor as usize] = pos as u32;
        *cursor += 1;
    }
    let mut lists: Vec<Vec<u32>> = vec![Vec::new(); h];
    for &pos in b.by_key() {
        let patient = patients[pos as usize];
        let list = &mut lists[usize::from(slot_by_pos[pos as usize])];
        if list.last() != Some(&patient) {
            list.push(patient);
        }
    }
    Ok(View {
        partitions,
        patients: lists.into_iter().map(sorted_bitmap).collect(),
        ends,
        positions,
        value_ends: Vec::new(),
        parts: Vec::new(),
    })
}

fn categorical_view(
    b: &CategoricalBlock,
    router: &Router<'_>,
    partitions: Box<[u16]>,
) -> Result<View> {
    let h = partitions.len();
    let patients = b.patients();
    let mut lists: Vec<Vec<u32>> = vec![Vec::new(); h];
    let mut tagged_parts: HashMap<(u32, u16), Vec<u32>> = HashMap::new();
    let values = b.value_indices();
    for (k, &patient) in patients.iter().enumerate() {
        let slot = router.slot(patient, b.tag_at_key_pos(k))?;
        let list = &mut lists[usize::from(slot)];
        if list.last() != Some(&patient) {
            list.push(patient);
        }
        if router.tagged {
            let part = tagged_parts.entry((values[k], slot)).or_default();
            if part.last() != Some(&patient) {
                part.push(patient);
            }
        }
    }
    let mut value_ends = Vec::with_capacity(b.dictionary_len());
    let mut parts = Vec::new();
    if router.tagged {
        let mut keys: Vec<(u32, u16)> = tagged_parts.keys().copied().collect();
        keys.sort_unstable();
        let mut keys = keys.into_iter().peekable();
        for value in 0..b.dictionary_len() as u32 {
            while let Some((_, slot)) = keys.next_if(|(v, _)| *v == value) {
                let list = tagged_parts.remove(&(value, slot)).unwrap_or_default();
                parts.push((slot, sorted_bitmap(list)));
            }
            value_ends.push(parts.len() as u32);
        }
    } else {
        let mut split: Vec<Vec<u32>> = vec![Vec::new(); h];
        for value in 0..b.dictionary_len() {
            for patient in b.bitmap(value) {
                split[usize::from(router.slot(patient, None)?)].push(patient);
            }
            for (slot, list) in split.iter_mut().enumerate() {
                if !list.is_empty() {
                    let patients = RoaringBitmap::from_sorted_iter(list.drain(..));
                    parts.push((slot as u16, patients.unwrap_or_default()));
                }
            }
            value_ends.push(parts.len() as u32);
        }
    }
    Ok(View {
        partitions,
        patients: lists.into_iter().map(sorted_bitmap).collect(),
        ends: Vec::new(),
        positions: Vec::new(),
        value_ends,
        parts,
    })
}

fn sorted_bitmap(list: Vec<u32>) -> RoaringBitmap {
    RoaringBitmap::from_sorted_iter(list).unwrap_or_default()
}
