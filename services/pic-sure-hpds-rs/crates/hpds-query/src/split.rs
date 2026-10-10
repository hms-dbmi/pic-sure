//! One partition's rows of a block, as the block a store of that partition alone would
//! hold: the rows of the partition in key order, encoded again. For the selection paths
//! that read partitions one at a time (narrow consent selections, and full ones with
//! `HPDS_COMBINED_STORE=off`).
//!
//! The rows of a partition keep their order within the block's key order, which is the
//! partition's own order within each patient, so the encoder sorts them into exactly the
//! arrays, dictionary and bitmaps the partition's own block would hold, and every answer
//! read from it is the partition's answer.

use std::sync::Arc;

use hpds_format::{
    AlignedBuf, Block, CategoricalRow, Membership, NumericObservation,
    encode_interned_categorical_payload, encode_numeric_payload,
};

use crate::error::{QueryError, Result};

/// The block holding only the rows of `block` that belong to `partition`: a row's tag in a
/// tagged block, otherwise the partition `membership` routes its patient to. `path` names
/// the concept in errors.
///
/// # Errors
/// [`QueryError::Block`] when re-encoding the rows fails, which a block that passed its
/// checks never does.
pub(crate) fn partition_block(
    block: &Block,
    partition: u16,
    membership: &Membership,
    path: &str,
) -> Result<Block> {
    let tagged = block.partitions().is_tagged();
    let belongs = |patient: u32, tag: Option<u16>| {
        let owner = if tagged {
            tag
        } else {
            membership.partition_of(patient)
        };
        owner == Some(partition)
    };
    let encoded = match block {
        Block::Numeric(b) => {
            let (values, patients) = (b.values(), b.patients());
            let rows: Vec<NumericObservation> = b
                .by_key()
                .iter()
                .map(|&i| i as usize)
                .filter(|&i| belongs(patients[i], b.tag_at_value_pos(i)))
                .map(|i| NumericObservation {
                    patient: patients[i],
                    value: values[i],
                    timestamp: b.timestamp_at_value_pos(i),
                })
                .collect();
            encode_numeric_payload(&rows)
        }
        Block::Categorical(b) => {
            let values: Vec<&str> = b.dictionary().collect();
            let indices = b.value_indices();
            let rows: Vec<CategoricalRow> = b
                .patients()
                .iter()
                .enumerate()
                .filter(|&(k, &patient)| belongs(patient, b.tag_at_key_pos(k)))
                .map(|(k, &patient)| CategoricalRow {
                    patient,
                    value_id: indices[k],
                    timestamp: b.timestamp_at_key_pos(k),
                })
                .collect();
            encode_interned_categorical_payload(&values, &rows)
        }
    };
    encoded
        .and_then(|payload| Block::decode(AlignedBuf::copy_from(&payload)))
        .map_err(|source| QueryError::Block {
            concept_path: path.to_owned(),
            source: Arc::new(source),
        })
}
