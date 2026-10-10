use hpds_format::{
    AlignedBuf, Block, BlockCodec, BlockLocation, BlockPartitioning, BlocksReader, BlocksWriter,
    CategoricalRow, ConceptMeta, ConceptType, CryptoKey, FormatError, IndexHeader, IndexPartition,
    LEGACY_FORMAT_VERSION, LegacyIndex, Membership, NO_TIMESTAMP, NumericObservation,
    PartitionHolder, PartitionTable, PhenoIndex, Protection, StoreId,
    encode_interned_categorical_block, encode_numeric_block, encode_numeric_payload,
};
use roaring::RoaringBitmap;
use xxhash_rust::xxh3::{Xxh3Default, xxh3_64};

fn store() -> StoreId {
    StoreId::from_bytes([7; 16])
}

fn header(encrypted: bool) -> IndexHeader {
    IndexHeader {
        format_version: 0,
        encrypted,
        compressed: true,
        key_name: String::new(),
        created_at_millis: 1,
        etl_version: "test".into(),
        store_id: store(),
    }
}

fn bitmap(ids: &[u32]) -> RoaringBitmap {
    ids.iter().copied().collect()
}

/// Two partitions: `a` holds patients 1 and 2, `b` holds 3 and 4, and patient 5 is in both,
/// so it is the overlap set, with rows tagged with either.
fn table() -> PartitionTable {
    PartitionTable {
        partitions: vec![
            IndexPartition {
                name: "a".into(),
                patients_indexed: true,
                patients: bitmap(&[1, 2, 5]),
                tagged: bitmap(&[5]),
            },
            IndexPartition {
                name: "b".into(),
                patients_indexed: false,
                patients: bitmap(&[3, 4, 5]),
                tagged: bitmap(&[5]),
            },
        ],
        overlap: bitmap(&[5]),
    }
}

fn numeric(patient: u32, value: f64) -> NumericObservation {
    NumericObservation {
        patient,
        value,
        timestamp: NO_TIMESTAMP,
    }
}

fn holder(partition: u16, observations: u32, patients: u32) -> PartitionHolder {
    PartitionHolder {
        partition,
        observations,
        patients,
    }
}

fn decode(payload: Vec<u8>) -> Block {
    Block::decode(AlignedBuf::copy_from(&payload)).unwrap()
}

#[test]
fn an_untagged_block_routes_rows_by_patient() {
    let rows = [
        numeric(3, 9.0),
        numeric(1, 5.0),
        numeric(1, 2.0),
        numeric(4, 1.0),
    ];
    let holders = [holder(0, 2, 1), holder(1, 2, 2)];
    let payload = encode_numeric_block(
        &rows,
        &BlockPartitioning {
            holders: &holders,
            row_partitions: None,
            partition_count: 2,
        },
    )
    .unwrap();
    let block = decode(payload);
    assert_eq!(block.partitions().holders(), holders);
    assert!(!block.partitions().is_tagged());
    let membership = table().membership();
    block
        .check_membership(&holders, false, &membership)
        .unwrap();
    let routed: Vec<(u32, Option<u16>)> = block.rows_by_key(&membership).collect();
    assert_eq!(
        routed,
        [(1, Some(0)), (1, Some(0)), (3, Some(1)), (4, Some(1))]
    );
    let wrong_counts = [holder(0, 2, 2), holder(1, 2, 2)];
    assert!(
        block
            .check_membership(&wrong_counts, false, &membership)
            .is_err()
    );
    assert!(block.check_membership(&holders, true, &membership).is_err());
}

#[test]
fn an_untagged_block_with_an_overlap_patient_fails_the_membership_check() {
    let rows = [numeric(5, 1.0), numeric(1, 2.0)];
    let holders = [holder(0, 2, 2)];
    let block = decode(
        encode_numeric_block(
            &rows,
            &BlockPartitioning {
                holders: &holders,
                row_partitions: None,
                partition_count: 2,
            },
        )
        .unwrap(),
    );
    let error = block
        .check_membership(&holders, false, &table().membership())
        .unwrap_err()
        .to_string();
    assert!(error.contains("no single partition holds"), "{error}");
}

#[test]
fn a_tagged_block_reads_partitions_from_its_tags() {
    let values = ["x", "y"];
    let rows = [
        CategoricalRow {
            patient: 5,
            value_id: 0,
            timestamp: NO_TIMESTAMP,
        },
        CategoricalRow {
            patient: 5,
            value_id: 1,
            timestamp: 7,
        },
        CategoricalRow {
            patient: 2,
            value_id: 1,
            timestamp: NO_TIMESTAMP,
        },
        CategoricalRow {
            patient: 3,
            value_id: 0,
            timestamp: NO_TIMESTAMP,
        },
    ];
    let tags = [1u16, 0, 0, 1];
    let holders = [holder(0, 2, 2), holder(1, 2, 2)];
    for partition_count in [2usize, 300] {
        let block = decode(
            encode_interned_categorical_block(
                &values,
                &rows,
                &BlockPartitioning {
                    holders: &holders,
                    row_partitions: Some(&tags),
                    partition_count,
                },
            )
            .unwrap(),
        );
        assert!(block.partitions().is_tagged());
        let Block::Categorical(categorical) = &block else {
            panic!("categorical");
        };
        let by_key: Vec<(u32, Option<u16>)> = (0..categorical.len())
            .map(|k| (categorical.patients()[k], categorical.tag_at_key_pos(k)))
            .collect();
        assert_eq!(
            by_key,
            [(2, Some(0)), (3, Some(1)), (5, Some(1)), (5, Some(0))]
        );
        if partition_count == 2 {
            block
                .check_membership(&holders, true, &table().membership())
                .unwrap();
        }
    }
    let lying = [1u16, 0, 1, 1];
    let block = decode(
        encode_interned_categorical_block(
            &values,
            &rows,
            &BlockPartitioning {
                holders: &[holder(0, 1, 1), holder(1, 3, 2)],
                row_partitions: Some(&lying),
                partition_count: 2,
            },
        )
        .unwrap(),
    );
    let error = block
        .check_membership(
            &[holder(0, 1, 1), holder(1, 3, 2)],
            true,
            &table().membership(),
        )
        .unwrap_err()
        .to_string();
    assert!(error.contains("tagged with another partition"), "{error}");
}

/// Holders, row partitions and partition count of one partitioning.
type Case<'a> = (&'a [PartitionHolder], Option<&'a [u16]>, usize);

#[test]
fn the_encoder_refuses_partitionings_that_do_not_fit_the_rows() {
    let rows = [numeric(1, 1.0), numeric(3, 2.0)];
    let cases: [Case<'_>; 5] = [
        (&[holder(0, 1, 1)], None, 2),
        (&[holder(1, 1, 1), holder(0, 1, 1)], None, 2),
        (&[holder(0, 1, 1), holder(2, 1, 1)], None, 2),
        (&[holder(0, 1, 1), holder(1, 1, 1)], Some(&[0, 0]), 2),
        (&[], Some(&[0, 0]), 2),
    ];
    for (holders, row_partitions, partition_count) in cases {
        let refused = encode_numeric_block(
            &rows,
            &BlockPartitioning {
                holders,
                row_partitions,
                partition_count,
            },
        );
        assert!(
            matches!(refused, Err(FormatError::InvalidInput(_))),
            "{holders:?} {row_partitions:?}"
        );
    }
}

#[test]
fn a_damaged_prefix_is_refused() {
    let rows = [numeric(1, 1.0), numeric(3, 2.0)];
    let holders = [holder(0, 1, 1), holder(1, 1, 1)];
    let payload = encode_numeric_block(
        &rows,
        &BlockPartitioning {
            holders: &holders,
            row_partitions: None,
            partition_count: 2,
        },
    )
    .unwrap();
    for (at, value) in [(0usize, 3u8), (5, 1), (6, 2), (7, 1), (12, 1), (20, 9)] {
        let mut damaged = payload.clone();
        damaged[at] = value;
        assert!(
            Block::decode(AlignedBuf::copy_from(&damaged)).is_err(),
            "byte {at}"
        );
    }
}

fn concept(path: &str, n: u32, holders: Vec<PartitionHolder>, tagged: bool) -> ConceptMeta {
    ConceptMeta {
        path: path.into(),
        concept_type: ConceptType::Numeric,
        category_values: None,
        min: None,
        max: None,
        observation_count: n,
        patient_count: 0,
        has_timestamp: false,
        timestamp_min: None,
        timestamp_max: None,
        width_in_bytes: 0,
        column_offset: 0,
        block: BlockLocation::default(),
        holders,
        tagged,
    }
}

#[test]
fn a_partitioned_index_round_trips_and_routes() {
    let concepts = vec![
        concept("\\b\\", 3, vec![holder(0, 1, 1), holder(1, 2, 2)], true),
        concept("\\a\\", 2, vec![holder(1, 2, 1)], false),
    ];
    for encrypted in [false, true] {
        let protection = if encrypted {
            Protection::Encrypted(CryptoKey::from_bytes(&[3; 32]).unwrap())
        } else {
            Protection::Plain
        };
        let index = PhenoIndex::with_partitions(
            header(encrypted),
            bitmap(&[1, 2, 3, 4, 5]),
            table(),
            concepts.clone(),
        )
        .unwrap();
        let decoded = PhenoIndex::decode(&index.encode(&protection).unwrap(), &protection).unwrap();
        assert_eq!(decoded, index);
        assert!(decoded.is_partitioned());
        assert_eq!(decoded.concepts()[0].path, "\\a\\");
        assert_eq!(decoded.concepts()[1].summed_patient_count(), 3);
        assert_eq!(decoded.partition_table().position("b"), Some(1));
        let membership = decoded.membership();
        assert_eq!(membership.partition_of(4), Some(1));
        assert_eq!(membership.partition_of(5), None);
    }
}

#[test]
fn an_index_whose_partitions_do_not_fit_is_refused() {
    let patients = bitmap(&[1, 2, 3, 4, 5]);
    let mut unordered = table();
    unordered.partitions.swap(0, 1);
    let mut short_union = table();
    short_union.partitions[1].patients.remove(4);
    let mut tags_outside = table();
    tags_outside.partitions[0].tagged.insert(1);
    for bad in [unordered, short_union, tags_outside] {
        assert!(PhenoIndex::with_partitions(header(false), patients.clone(), bad, vec![]).is_err());
    }
    let concepts: [ConceptMeta; 4] = [
        concept("\\x\\", 2, vec![holder(1, 1, 1), holder(0, 1, 1)], false),
        concept("\\x\\", 2, vec![holder(0, 1, 1), holder(2, 1, 1)], false),
        concept("\\x\\", 3, vec![holder(0, 1, 1), holder(1, 1, 1)], false),
        concept("\\x\\", 2, vec![holder(0, 2, 1)], true),
    ];
    let mut no_overlap = table();
    no_overlap.overlap = RoaringBitmap::new();
    for (i, bad) in concepts.into_iter().enumerate() {
        let table = if i == 3 { no_overlap.clone() } else { table() };
        assert!(
            PhenoIndex::with_partitions(header(false), patients.clone(), table, vec![bad]).is_err(),
            "case {i}"
        );
    }
    assert!(
        PhenoIndex::new(
            header(false),
            patients,
            vec![concept("\\x\\", 1, vec![holder(0, 1, 1)], false)]
        )
        .is_err()
    );
}

#[test]
fn a_block_disagreeing_with_its_index_entry_is_refused() {
    let dir = tempfile::tempdir().unwrap();
    let path = dir.path().join("pheno.blocks");
    let codec = BlockCodec::new(Protection::Plain, store(), true);
    let holders = vec![holder(0, 1, 1), holder(1, 1, 1)];
    let rows = [numeric(1, 1.0), numeric(3, 2.0)];
    let mut writer = BlocksWriter::new(std::fs::File::create(&path).unwrap(), store()).unwrap();
    let payload = encode_numeric_block(
        &rows,
        &BlockPartitioning {
            holders: &holders,
            row_partitions: None,
            partition_count: 2,
        },
    )
    .unwrap();
    let mut meta = concept("\\n\\", 2, holders.clone(), false);
    meta.block = writer.append(&codec, "\\n\\", &payload).unwrap();
    writer.finish().unwrap();
    let reader = BlocksReader::open(&path, store()).unwrap();
    let membership = table().membership();
    reader.read_block(&codec, &meta, &membership).unwrap();
    let mut wrong_holders = meta.clone();
    wrong_holders.holders = vec![holder(0, 2, 2)];
    let mut wrong_count = meta.clone();
    wrong_count.observation_count = 3;
    let mut wrong_tagging = meta.clone();
    wrong_tagging.tagged = true;
    for bad in [wrong_holders, wrong_count, wrong_tagging] {
        assert!(matches!(
            reader.read_block(&codec, &bad, &membership),
            Err(FormatError::Corrupt { .. })
        ));
    }
}

/// A format 1 index for one concept and plain protection, uncompressed, written by hand as
/// Rust HPDS v1 to v3 wrote it.
fn legacy_index(patients: &RoaringBitmap, path: &str, location: BlockLocation, n: u32) -> Vec<u8> {
    let mut out = Vec::new();
    out.extend_from_slice(b"HPDSPHN1");
    out.extend_from_slice(&LEGACY_FORMAT_VERSION.to_le_bytes());
    out.extend_from_slice(&0u16.to_le_bytes());
    out.extend_from_slice(store().as_bytes());
    out.extend_from_slice(&0u32.to_le_bytes());
    out.extend_from_slice(&1i64.to_le_bytes());
    out.extend_from_slice(&3u32.to_le_bytes());
    out.extend_from_slice(b"old");
    let mut body = Vec::new();
    body.extend_from_slice(&(patients.serialized_size() as u64).to_le_bytes());
    patients.serialize_into(&mut body).unwrap();
    body.extend_from_slice(&1u32.to_le_bytes());
    body.extend_from_slice(&(path.len() as u32).to_le_bytes());
    body.extend_from_slice(path.as_bytes());
    body.push(0);
    body.push(0);
    body.extend_from_slice(&location.offset.to_le_bytes());
    body.extend_from_slice(&location.length.to_le_bytes());
    body.extend_from_slice(&n.to_le_bytes());
    body.extend_from_slice(&1u32.to_le_bytes());
    body.extend_from_slice(&[0u8; 8]);
    body.extend_from_slice(&[0u8; 32]);
    body.extend_from_slice(&0u32.to_le_bytes());
    out.extend_from_slice(&(body.len() as u64).to_le_bytes());
    out.extend_from_slice(&body);
    let checksum = xxh3_64(&out);
    out.extend_from_slice(&checksum.to_le_bytes());
    out
}

/// A plain, uncompressed format 1 block: the body alone and its checksum over associated
/// data that binds format version 1.
fn legacy_block(path: &str, body: &[u8]) -> Vec<u8> {
    let mut hasher = Xxh3Default::new();
    hasher.update(store().as_bytes());
    hasher.update(path.as_bytes());
    hasher.update(&LEGACY_FORMAT_VERSION.to_le_bytes());
    hasher.update(body);
    let mut out = body.to_vec();
    out.extend_from_slice(&hasher.digest().to_le_bytes());
    out
}

#[test]
fn format_1_stores_are_read_only_through_the_legacy_reader() {
    let rows = [numeric(1, 1.0), numeric(1, 3.0)];
    let v4 = encode_numeric_payload(&rows).unwrap();
    let body = &v4[16..];
    let stored = legacy_block("\\n\\", body);
    let location = BlockLocation {
        offset: 24,
        length: stored.len() as u64,
    };
    let bytes = legacy_index(&bitmap(&[1]), "\\n\\", location, 2);
    assert!(matches!(
        PhenoIndex::decode(&bytes, &Protection::Plain),
        Err(FormatError::LegacyFormat)
    ));
    assert!(
        PhenoIndex::decode(&bytes, &Protection::Plain)
            .unwrap_err()
            .to_string()
            .contains("hpds-etl convert")
    );
    let legacy = LegacyIndex::decode(&bytes, &Protection::Plain).unwrap();
    assert_eq!(legacy.concepts().len(), 1);
    assert_eq!(legacy.patients(), &bitmap(&[1]));
    let codec = legacy.block_codec(Protection::Plain).unwrap();
    assert!(!legacy.header().compressed);
    let block = codec.open("\\n\\", &stored, 1 << 20).unwrap();
    assert_eq!(block.len(), 2);
    assert!(block.partitions().holders().is_empty());
    assert!(codec.seal("\\n\\", body).is_err());
    let current = BlockCodec::new(Protection::Plain, store(), false);
    assert!(matches!(
        current.open("\\n\\", &stored, 1 << 20),
        Err(FormatError::Checksum(_))
    ));

    let dir = tempfile::tempdir().unwrap();
    let path = dir.path().join("pheno.blocks");
    let mut file = b"HPDSBLK1".to_vec();
    file.extend_from_slice(store().as_bytes());
    file.extend_from_slice(&stored);
    std::fs::write(&path, &file).unwrap();
    let reader = BlocksReader::open(&path, store()).unwrap();
    let read = reader
        .read_legacy_block(&codec, &legacy.concepts()[0])
        .unwrap();
    assert_eq!(read.all_patients(), &bitmap(&[1]));
    assert!(matches!(
        LegacyIndex::decode(
            &PhenoIndex::new(header(false), bitmap(&[1]), vec![])
                .unwrap()
                .encode(&Protection::Plain)
                .unwrap(),
            &Protection::Plain
        ),
        Err(FormatError::UnsupportedVersion(2))
    ));
    let _ = Membership::unpartitioned();
}

#[test]
fn a_patient_in_no_partition_set_is_an_overlap_patient_read_by_tag() {
    let mut gap = table();
    gap.overlap.insert(9);
    gap.partitions[0].tagged.insert(9);
    gap.partitions[1].tagged.insert(9);
    let holders = vec![holder(0, 2, 2), holder(1, 1, 1)];
    let index = PhenoIndex::with_partitions(
        header(false),
        bitmap(&[1, 2, 3, 4, 5]),
        gap.clone(),
        vec![concept("\\n\\", 3, holders.clone(), true)],
    )
    .unwrap();
    let decoded = PhenoIndex::decode(
        &index.encode(&Protection::Plain).unwrap(),
        &Protection::Plain,
    )
    .unwrap();
    assert_eq!(decoded, index);
    assert!(!decoded.patients().contains(9));
    let membership = decoded.membership();
    assert_eq!(membership.partition_of(9), None);
    let rows = [numeric(9, 1.0), numeric(1, 2.0), numeric(9, 3.0)];
    let block = decode(
        encode_numeric_block(
            &rows,
            &BlockPartitioning {
                holders: &holders,
                row_partitions: Some(&[0, 0, 1]),
                partition_count: 2,
            },
        )
        .unwrap(),
    );
    block.check_membership(&holders, true, &membership).unwrap();
    let mut untracked = gap.clone();
    untracked.partitions[1].tagged.remove(9);
    let error = block
        .check_membership(&holders, true, &untracked.membership())
        .unwrap_err()
        .to_string();
    assert!(error.contains("tagged set lacks"), "{error}");
    let untagged_holders = [holder(0, 3, 2)];
    let untagged = decode(
        encode_numeric_block(
            &rows,
            &BlockPartitioning {
                holders: &untagged_holders,
                row_partitions: None,
                partition_count: 2,
            },
        )
        .unwrap(),
    );
    assert!(
        untagged
            .check_membership(&untagged_holders, false, &membership)
            .is_err()
    );
}
