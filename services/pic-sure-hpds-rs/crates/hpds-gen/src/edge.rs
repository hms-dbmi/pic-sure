//! The edge-case dataset: a small file exercising every ETL and parity rule, a few patients each.

use std::io::Write;

use crate::generate::HEADER;

/// One CSV record, possibly shorter or longer than the five-column header.
pub type Record = Vec<String>;

fn full(patient: u32, path: &str, number: &str, text: &str, stamp: &str) -> Record {
    vec![
        patient.to_string(),
        path.to_string(),
        number.to_string(),
        text.to_string(),
        stamp.to_string(),
    ]
}

fn numeric(patient: u32, path: &str, number: &str) -> Record {
    full(patient, path, number, "", "")
}

fn text(patient: u32, path: &str, value: &str) -> Record {
    full(patient, path, "", value, "")
}

/// Plain rows of both types with timestamps, the control that every other case departs from.
fn baseline(base: u32) -> Vec<Record> {
    vec![
        numeric(base, "\\edge\\baseline\\age\\", "42"),
        numeric(base + 1, "\\edge\\baseline\\age\\", "-7.25"),
        text(base, "\\edge\\baseline\\sex\\", "female"),
        full(
            base + 1,
            "\\edge\\baseline\\sex\\",
            "",
            "male",
            "1700000000000",
        ),
        full(base + 2, "\\edge\\baseline\\age\\", "0", "", "946684800000"),
    ]
}

/// Concept path normalization: the micro sign `µ` is replaced by a backslash before splitting,
/// including leading, doubled and trailing occurrences.
fn mu_in_path(base: u32) -> Vec<Record> {
    vec![
        numeric(base, "µedgeµmuµvar_a", "1"),
        numeric(base + 1, "\\edge\\mu\\var_a\\", "2"),
        text(base + 2, "edgeµmuµvar_bµ", "mu_text"),
        numeric(base, "\\edgeµµmu2\\var_c", "3"),
    ]
}

/// Path normalization: every node is trimmed, so padded nodes merge with unpadded ones.
fn path_node_spaces(base: u32) -> Vec<Record> {
    vec![
        numeric(base, "\\edge\\spaces\\var_a\\", "10"),
        numeric(base + 1, "\\ edge \\  spaces\\var_a  \\", "11"),
        numeric(base + 2, " \\edge\\spaces \\ var_a", "12"),
        text(base, "\\edge\\ spaces \\\tvar_b\t\\", "tabbed"),
    ]
}

/// Path normalization: a trailing backslash is appended, so paths with and without it are the
/// same concept.
fn missing_trailing_backslash(base: u32) -> Vec<Record> {
    vec![
        numeric(base, "\\edge\\trailing\\var_a", "1"),
        numeric(base + 1, "\\edge\\trailing\\var_a\\", "2"),
        text(base + 2, "edge\\trailing\\var_b", "no_leading_slash"),
    ]
}

/// Path normalization: empty interior nodes survive, while empty trailing nodes are dropped by
/// the split before the trailing backslash is appended.
fn empty_path_nodes(base: u32) -> Vec<Record> {
    vec![
        numeric(base, "\\edge\\\\empty\\var_a\\", "1"),
        numeric(base + 1, "\\edge\\empty2\\var_a\\\\\\", "2"),
        numeric(base + 2, "\\\\edge\\empty3\\var_a\\", "3"),
    ]
}

/// U+FFFD is stripped from concept paths but kept inside values.
fn replacement_char(base: u32) -> Vec<Record> {
    vec![
        numeric(base, "\\edge\\ffd\\va\u{FFFD}r_a\\", "1"),
        numeric(base + 1, "\\edge\\ffd\\var_a\\", "2"),
        text(base, "\\edge\\ffd\\var_b\\", "val\u{FFFD}ue"),
        text(base + 1, "\\edge\\ffd\\var_b\\", "\u{FFFD}"),
        text(base + 2, "\\edge\\ffd\\var_b\\", "plain"),
    ]
}

/// Rollup: a path ending in `\<trimmed text value>\` drops its last node, including when the
/// value is padded or contains U+FFFD that is stripped before the comparison, and rows that do
/// not match stay under the full path.
fn rollup(base: u32) -> Vec<Record> {
    vec![
        text(base, "\\edge\\rollup\\question\\Yes\\", "Yes"),
        text(base + 1, "\\edge\\rollup\\question\\No\\", "No"),
        text(base + 2, "\\edge\\rollup\\question\\Yes\\", "  Yes  "),
        text(base, "\\edge\\rollup\\other\\Maybe\\", "May\u{FFFD}be"),
        text(base + 1, "\\edge\\rollup\\other\\Maybe\\", "Different"),
        text(base + 2, "\\edge\\rollup\\Case\\", "case"),
        text(base + 2, "\\edge\\rollup\\Upper\\Upper\\", "upper"),
    ]
}

/// Type fixing: the first row of a concept in file order is numeric, so later text rows are
/// dropped.
fn numeric_then_text(base: u32) -> Vec<Record> {
    vec![
        numeric(base, "\\edge\\mixed\\num_first\\", "5"),
        text(base + 1, "\\edge\\mixed\\num_first\\", "dropped"),
        numeric(base + 2, "\\edge\\mixed\\num_first\\", "6"),
        text(base + 2, "\\edge\\mixed\\num_first\\", "dropped_too"),
    ]
}

/// Type fixing: the first row of a concept in file order is text, so later numeric rows are
/// dropped.
fn text_then_numeric(base: u32) -> Vec<Record> {
    vec![
        text(base, "\\edge\\mixed\\text_first\\", "kept"),
        numeric(base + 1, "\\edge\\mixed\\text_first\\", "9"),
        text(base + 2, "\\edge\\mixed\\text_first\\", "kept_too"),
        numeric(base + 2, "\\edge\\mixed\\text_first\\", "10"),
    ]
}

/// Type fixing by a rejected row: Java creates the concept store for the first row's type
/// before validating that row, so an unparseable numeric first row still fixes the type to
/// numeric.
fn type_fixed_by_rejected_numeric(base: u32) -> Vec<Record> {
    vec![
        numeric(base, "\\edge\\mixed\\bad_numeric_first\\", "not_a_number"),
        numeric(base + 1, "\\edge\\mixed\\bad_numeric_first\\", "3"),
        text(base + 2, "\\edge\\mixed\\bad_numeric_first\\", "dropped"),
    ]
}

/// Type fixing by a rejected row: a first row with blank NVAL_NUM and blank TVAL_CHAR is
/// rejected as empty text, yet Java has already fixed the concept as text, so later numeric
/// rows are dropped.
fn type_fixed_by_rejected_blank(base: u32) -> Vec<Record> {
    vec![
        full(base, "\\edge\\mixed\\blank_first\\", "", "", ""),
        numeric(base + 1, "\\edge\\mixed\\blank_first\\", "3"),
        text(base + 2, "\\edge\\mixed\\blank_first\\", "kept"),
    ]
}

/// Numeric parsing: `Double.parseDouble` accepts NaN, Infinity, signed infinities, and the
/// suffixes `d` and `f`, so the Rust parser must as well.
fn java_double_literals(base: u32) -> Vec<Record> {
    vec![
        numeric(base, "\\edge\\num\\special\\", "NaN"),
        numeric(base + 1, "\\edge\\num\\special\\", "Infinity"),
        numeric(base + 2, "\\edge\\num\\special\\", "-Infinity"),
        numeric(base + 3, "\\edge\\num\\special\\", "+Infinity"),
        numeric(base + 4, "\\edge\\num\\special\\", "1.5d"),
        numeric(base + 5, "\\edge\\num\\special\\", "2.5F"),
        numeric(base + 6, "\\edge\\num\\special\\", "1e3"),
        numeric(base + 7, "\\edge\\num\\special\\", ".5"),
        numeric(base + 8, "\\edge\\num\\special\\", "5."),
        numeric(base + 9, "\\edge\\num\\special\\", "+3"),
    ]
}

/// Numeric parsing: hexadecimal floating point literals are valid Java doubles.
fn hex_floats(base: u32) -> Vec<Record> {
    vec![
        numeric(base, "\\edge\\num\\hex\\", "0x1.8p1"),
        numeric(base + 1, "\\edge\\num\\hex\\", "0X1P-2"),
        numeric(base + 2, "\\edge\\num\\hex\\", "-0x1.0p3"),
    ]
}

/// Numeric parsing: strings Java rejects, which must be dropped rather than loaded.
fn unparseable_numbers(base: u32) -> Vec<Record> {
    vec![
        numeric(base, "\\edge\\num\\invalid\\", "1"),
        numeric(base + 1, "\\edge\\num\\invalid\\", "abc"),
        numeric(base + 2, "\\edge\\num\\invalid\\", "1,5"),
        numeric(base + 3, "\\edge\\num\\invalid\\", "--1"),
        numeric(base + 4, "\\edge\\num\\invalid\\", "1_000"),
        numeric(base + 5, "\\edge\\num\\invalid\\", "nan"),
        numeric(base + 6, "\\edge\\num\\invalid\\", "0x10"),
        numeric(base + 7, "\\edge\\num\\invalid\\", "infinity"),
    ]
}

/// Double ordering: `-0.0` sorts before `0.0` and the two stay distinct values.
fn signed_zero(base: u32) -> Vec<Record> {
    vec![
        numeric(base, "\\edge\\num\\zero\\", "-0.0"),
        numeric(base + 1, "\\edge\\num\\zero\\", "0.0"),
        numeric(base + 2, "\\edge\\num\\zero\\", "0"),
        numeric(base + 3, "\\edge\\num\\zero\\", "-0"),
    ]
}

/// Numeric values with extreme magnitude and precision, including subnormals and a 400 digit
/// literal.
fn numeric_extremes(base: u32) -> Vec<Record> {
    let long_decimal = format!("0.{}1", "0".repeat(400));
    vec![
        numeric(base, "\\edge\\num\\extreme\\", "1.7976931348623157E308"),
        numeric(base + 1, "\\edge\\num\\extreme\\", "4.9E-324"),
        numeric(base + 2, "\\edge\\num\\extreme\\", "1e999"),
        numeric(base + 3, "\\edge\\num\\extreme\\", &long_decimal),
        numeric(base + 4, "\\edge\\num\\extreme\\", "9007199254740993"),
    ]
}

/// Whitespace: numeric and text values are trimmed before use.
fn whitespace_values(base: u32) -> Vec<Record> {
    vec![
        numeric(base, "\\edge\\ws\\num\\", "  42  "),
        numeric(base + 1, "\\edge\\ws\\num\\", "\t7\t"),
        text(base, "\\edge\\ws\\txt\\", "  padded  "),
        text(base + 1, "\\edge\\ws\\txt\\", "padded"),
        text(base + 2, "\\edge\\ws\\txt\\", "\tinner space\t"),
    ]
}

/// Blank values: a blank NVAL_NUM (including whitespace only) makes the row text, and text
/// rows whose trimmed value is empty are dropped.
fn blank_values(base: u32) -> Vec<Record> {
    vec![
        full(base, "\\edge\\blank\\both\\", "", "", ""),
        full(base + 1, "\\edge\\blank\\both\\", "", "   ", ""),
        full(base + 2, "\\edge\\blank\\both\\", "", "kept", ""),
        full(base, "\\edge\\blank\\ws_numeric\\", "   ", "text_wins", ""),
        full(base + 1, "\\edge\\blank\\ws_numeric\\", " ", " ", ""),
        full(base + 2, "\\edge\\blank\\ws_numeric\\", "", "other", ""),
    ]
}

/// Short rows: rows with two or three columns are skipped (a one column row crashes the Java
/// low-RAM loader while it sorts by concept, so none is written), and a patient who only appears in
/// skipped or rejected rows is not in the patient set.
fn short_rows(base: u32) -> Vec<Record> {
    vec![
        vec![base.to_string(), "\\edge\\short\\var_a\\".to_string()],
        vec![
            (base + 1).to_string(),
            "\\edge\\short\\var_a\\".to_string(),
            "5".to_string(),
        ],
        numeric(base + 2, "\\edge\\short\\var_a\\", "6"),
    ]
}

/// Four-column rows have no timestamp column and load without a timestamp.
fn four_columns_without_timestamp(base: u32) -> Vec<Record> {
    vec![
        vec![
            base.to_string(),
            "\\edge\\four\\var_a\\".to_string(),
            "5".to_string(),
            String::new(),
        ],
        vec![
            (base + 1).to_string(),
            "\\edge\\four\\var_b\\".to_string(),
            String::new(),
            "four_col_text".to_string(),
        ],
    ]
}

/// Timestamps: epoch milliseconds parse, anything else (text, decimals, overflow, padding)
/// becomes no timestamp, and a negative value is a valid instant before 1970.
fn timestamps(base: u32) -> Vec<Record> {
    let stamps = [
        "1700000000000",
        "abc",
        "1.5",
        "12345678901234567890",
        " 1000",
        "-5",
        "0",
        "",
    ];
    stamps
        .iter()
        .enumerate()
        .map(|(offset, stamp)| {
            full(
                base + offset as u32,
                "\\edge\\time\\var_a\\",
                &offset.to_string(),
                "",
                stamp,
            )
        })
        .collect()
}

/// String order: Java compares UTF-16 code units, so a character above U+FFFF (a surrogate
/// pair) sorts before U+E000 through U+FFFF, the opposite of Rust code point order.
fn utf16_ordering(base: u32) -> Vec<Record> {
    vec![
        text(base, "\\edge\\utf16\\cat\\", "\u{FF5E}"),
        text(base + 1, "\\edge\\utf16\\cat\\", "\u{10000}"),
        text(base + 2, "\\edge\\utf16\\cat\\", "\u{E000}"),
        text(base + 3, "\\edge\\utf16\\cat\\", "\u{1F600}"),
        text(base + 4, "\\edge\\utf16\\cat\\", "\u{FFFC}"),
        text(base + 5, "\\edge\\utf16\\cat\\", "z"),
        text(base + 6, "\\edge\\utf16\\cat\\", "\u{1F600}\u{1F600}"),
        text(base + 7, "\\edge\\utf16\\cat\\", "\u{FF5E}a"),
        text(base, "\\edge\\utf16\\\u{1F600}path\\", "supplementary_path"),
    ]
}

/// Duplicate observations: identical rows, and the same patient with several values of one
/// concept, with and without distinct timestamps.
fn duplicates(base: u32) -> Vec<Record> {
    vec![
        numeric(base, "\\edge\\dup\\num\\", "5"),
        numeric(base, "\\edge\\dup\\num\\", "5"),
        numeric(base, "\\edge\\dup\\num\\", "6"),
        text(base + 1, "\\edge\\dup\\cat\\", "same"),
        text(base + 1, "\\edge\\dup\\cat\\", "same"),
        text(base + 1, "\\edge\\dup\\cat\\", "different"),
        full(base + 2, "\\edge\\dup\\timed\\", "1", "", "1000"),
        full(base + 2, "\\edge\\dup\\timed\\", "1", "", "1000"),
        full(base + 2, "\\edge\\dup\\timed\\", "1", "", "2000"),
        full(base + 2, "\\edge\\dup\\timed\\", "2", "", "1500"),
    ]
}

/// Unicode in paths and values outside the basic plane and in accented, CJK and RTL scripts.
fn unicode_text(base: u32) -> Vec<Record> {
    vec![
        text(base, "\\étude_日本語\\catégorie\\变量\\", "Ünïcödé"),
        text(base + 1, "\\étude_日本語\\catégorie\\变量\\", "日本語"),
        numeric(base + 2, "\\edge\\unicode\\עברית\\", "1"),
        text(base + 2, "\\edge\\unicode\\emoji\\", "\u{1F9EC}"),
    ]
}

/// Very long values and path nodes.
fn long_values(base: u32) -> Vec<Record> {
    let long_text = "x".repeat(20_000);
    let long_node = format!("\\edge\\long\\{}\\", "n".repeat(4_000));
    vec![
        text(base, "\\edge\\long\\text\\", &long_text),
        text(base + 1, "\\edge\\long\\text\\", &format!("{long_text}y")),
        numeric(base + 2, &long_node, "1"),
    ]
}

/// CSV quoting: fields containing commas and quotes round trip.
fn csv_quoting(base: u32) -> Vec<Record> {
    vec![
        text(base, "\\edge\\quote\\cat\\", "a,b"),
        text(base + 1, "\\edge\\quote\\cat\\", "say \"hi\""),
        numeric(base, "\\edge\\quote,comma\\num\\", "1"),
    ]
}

/// Patient IDs: leading zeros and a plus sign parse as integers, and the largest 32-bit ID is
/// accepted.
fn patient_id_forms(_base: u32) -> Vec<Record> {
    let row = |patient: &str, number: &str| -> Record {
        vec![
            patient.to_string(),
            "\\edge\\ids\\var_a\\".to_string(),
            number.to_string(),
            String::new(),
            String::new(),
        ]
    };
    vec![row("00900", "1"), row("+901", "2"), row("2147483647", "3")]
}

/// Concepts where every row is rejected: they hold no usable data, and the patients on them
/// appear nowhere else.
fn only_rejected_rows(base: u32) -> Vec<Record> {
    vec![
        numeric(base, "\\edge\\rejected\\all_bad\\", "oops"),
        numeric(base + 1, "\\edge\\rejected\\all_bad\\", "also_bad"),
        full(base + 2, "\\edge\\rejected\\all_blank\\", "", "  ", ""),
    ]
}

/// Repeated timestamped observations of one numeric and one categorical concept per patient.
fn repeated_observations(base: u32) -> Vec<Record> {
    let mut rows = Vec::new();
    for patient in 0..3u32 {
        for visit in 0..4u32 {
            let stamp = (1_600_000_000_000i64 + i64::from(visit) * 86_400_000).to_string();
            rows.push(full(
                base + patient,
                "\\edge\\repeat\\weight\\",
                &format!("{}.5", 60 + patient * 5 + visit),
                "",
                &stamp,
            ));
            rows.push(full(
                base + patient,
                "\\edge\\repeat\\stage\\",
                "",
                &format!("stage_{}", visit % 3),
                &stamp,
            ));
        }
    }
    rows
}

/// Consent concepts so the authorization filters and open aggregates work on this file too.
fn consent_concepts(base: u32) -> Vec<Record> {
    let mut rows = Vec::new();
    for offset in 0..3u32 {
        let consent = if offset == 1 {
            "phs000002.c1"
        } else {
            "phs000001.c1"
        };
        let study = if offset == 1 {
            "phs000002"
        } else {
            "phs000001"
        };
        rows.push(text(base + offset, "\\_consents\\", consent));
        rows.push(text(base + offset, "\\_studies_consents\\", consent));
        rows.push(text(base + offset, "\\_studies\\", study));
    }
    rows
}

const CASES: [fn(u32) -> Vec<Record>; 30] = [
    baseline,
    mu_in_path,
    path_node_spaces,
    missing_trailing_backslash,
    empty_path_nodes,
    replacement_char,
    rollup,
    numeric_then_text,
    text_then_numeric,
    type_fixed_by_rejected_numeric,
    type_fixed_by_rejected_blank,
    java_double_literals,
    hex_floats,
    unparseable_numbers,
    signed_zero,
    numeric_extremes,
    whitespace_values,
    blank_values,
    short_rows,
    four_columns_without_timestamp,
    timestamps,
    utf16_ordering,
    duplicates,
    unicode_text,
    long_values,
    csv_quoting,
    patient_id_forms,
    only_rejected_rows,
    repeated_observations,
    consent_concepts,
];

const PATIENTS_PER_CASE: u32 = 20;

/// Writes the edge-case dataset to `sink` and returns the number of records after the header.
///
/// Each case gets its own block of 20 patient IDs starting at 1000, so patients never collide
/// between cases. No negative patient IDs are written, see [`write_negative_ids`].
///
/// # Errors
/// Returns any I/O or CSV error from the sink.
pub fn write_edge_cases<W: Write>(sink: W) -> csv::Result<usize> {
    let mut out = csv::WriterBuilder::new().flexible(true).from_writer(sink);
    out.write_record(HEADER)?;
    let mut count = 0;
    for (index, case) in CASES.iter().enumerate() {
        let base = 1000 + index as u32 * PATIENTS_PER_CASE;
        for record in case(base) {
            out.write_record(&record)?;
            count += 1;
        }
    }
    out.flush()?;
    Ok(count)
}

/// Writes a minimal file whose only data row has a quoted field with an embedded newline.
///
/// It is kept apart from the main edge file because the Java low-RAM loader compares the
/// physical line count to the record count and silently ingests nothing when they differ.
///
/// # Errors
/// Returns any I/O or CSV error from the sink.
pub fn write_embedded_newline<W: Write>(sink: W) -> csv::Result<()> {
    let mut out = csv::WriterBuilder::new().from_writer(sink);
    out.write_record(HEADER)?;
    out.write_record(["1", "\\edge\\newline\\cat\\", "", "line1\nline2", ""])?;
    out.write_record(["2", "\\edge\\newline\\cat\\", "", "plain", ""])?;
    out.write_record(["3", "\\edge\\newline\\cat\\", "", "plain", ""])?;
    out.flush()?;
    Ok(())
}

/// Writes a minimal file whose single data row has a negative patient ID, which the Rust ETL
/// rejects by design.
///
/// # Errors
/// Returns any I/O or CSV error from the sink.
pub fn write_negative_ids<W: Write>(sink: W) -> csv::Result<()> {
    let mut out = csv::WriterBuilder::new().from_writer(sink);
    out.write_record(HEADER)?;
    out.write_record(["-5", "\\edge\\negative\\var_a\\", "1", "", ""])?;
    out.write_record(["7", "\\edge\\negative\\var_a\\", "2", "", ""])?;
    out.flush()?;
    Ok(())
}
