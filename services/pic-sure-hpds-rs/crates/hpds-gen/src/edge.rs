//! The edge-case datasets: small files exercising every ETL and parity rule, a few patients
//! per case.
//!
//! The cases are split by which Java loader survives them. `CSVLoaderNewSearch` throws on a
//! numeric value `Double.parseDouble` rejects and on a timestamp `Long.parseLong` rejects, and
//! stops the whole load, while the LowRAM loader drops such rows. So the core file holds only
//! rows both loaders get through, and the LowRAM file adds the rest.

use std::fs;
use std::io::{self, Write};
use std::path::{Path, PathBuf};

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
///
/// The LowRAM loader sorts rows by the raw path before ingesting and caches one concept at a
/// time. `µedgeµmuµvar_a` sorts after every path that starts with a backslash, far from
/// `\edge\mu\var_a\`, so the concept is ingested in two runs and the second replaces the
/// first: Java keeps only patient `base` with value 1. `CSVLoaderNewSearch` sees the rows
/// adjacent and keeps both. Rust deliberately keeps both rows in every mode.
fn mu_in_path(base: u32) -> Vec<Record> {
    vec![
        numeric(base, "µedgeµmuµvar_a", "1"),
        numeric(base + 1, "\\edge\\mu\\var_a\\", "2"),
        text(base + 2, "edgeµmuµvar_bµ", "mu_text"),
        numeric(base, "\\edgeµµmu2\\var_c", "3"),
    ]
}

/// Path normalization: every node is trimmed, so padded nodes merge with unpadded ones.
///
/// The three spellings of `\edge\spaces\var_a\` sort apart in the LowRAM loader (a leading
/// space sorts before a backslash, `\ edge` before `\_consents\`), so its one-concept cache
/// replaces the earlier runs and Java keeps only the value 10. `CSVLoaderNewSearch` keeps all
/// three, and Rust deliberately keeps all three in every mode.
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
///
/// The raw path `\edge\ffd\va\u{FFFD}r_a\` sorts after `\edge\ffd\var_b\`, so the LowRAM
/// loader ingests `\edge\ffd\var_a\` in two runs and keeps only the second (value 1).
/// `CSVLoaderNewSearch` keeps both values, and so does Rust, deliberately.
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
/// not match stay under the full path. Only the LowRAM loader rolls up by default;
/// `CSVLoaderNewSearch` never does.
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
/// numeric. LowRAM only: `CSVLoaderNewSearch` throws on the first row.
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

/// Numeric parsing: `Double.parseDouble` accepts NaN (signed too), Infinity, signed
/// infinities, and the suffixes `d` and `f`, also on hex literals, so the Rust parser must as
/// well.
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
        numeric(base + 10, "\\edge\\num\\special\\", "-NaN"),
        numeric(base + 11, "\\edge\\num\\special\\", "0x1p1d"),
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

/// Numeric parsing: strings Java rejects, which must be dropped rather than loaded. LowRAM
/// only: `CSVLoaderNewSearch` throws on the first of them and loads nothing.
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

/// Blank values: in the LowRAM loader a blank NVAL_NUM (`Strings.isBlank`, so whitespace
/// only too) makes the row text, and text rows whose trimmed value is empty are dropped.
///
/// `CSVLoaderNewSearch` tests `isEmpty` instead, so a whitespace-only NVAL_NUM is a numeric row
/// with an empty value: it is skipped, yet it fixes `\edge\blank\ws_numeric\` as numeric, and
/// the later text row is dropped too, leaving that concept empty. Rust follows the LowRAM rule.
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

fn timestamp_rows(base: u32, path: &str, stamps: &[&str]) -> Vec<Record> {
    stamps
        .iter()
        .enumerate()
        .map(|(offset, stamp)| full(base + offset as u32, path, &offset.to_string(), "", stamp))
        .collect()
}

/// Timestamps: epoch milliseconds parse with `Long.parseLong`, so a sign, a negative value
/// before 1970 and the full `i64` range are valid, and an empty field is no timestamp.
fn timestamps(base: u32) -> Vec<Record> {
    timestamp_rows(
        base,
        "\\edge\\time\\var_a\\",
        &[
            "1700000000000",
            "-5",
            "0",
            "",
            "+5",
            "-9223372036854775808",
            "9223372036854775807",
        ],
    )
}

/// Timestamps `Long.parseLong` rejects (text, decimals, overflow, padding): the LowRAM loader
/// loads the row without a timestamp. LowRAM only: `CSVLoaderNewSearch` throws on them.
fn bad_timestamps(base: u32) -> Vec<Record> {
    timestamp_rows(
        base,
        "\\edge\\time\\bad\\",
        &[
            "abc",
            "1.5",
            "12345678901234567890",
            " 1000",
            "1700000000000",
        ],
    )
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
/// appear nowhere else. LowRAM only: `CSVLoaderNewSearch` throws on the unparseable numbers.
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

/// Consent concepts so the authorization filters and open aggregates work on this file too,
/// with `\_studies_consents\` as a tree like the generated datasets.
fn consent_concepts(base: u32) -> Vec<Record> {
    let mut rows = Vec::new();
    for (offset, (study, group)) in [
        ("phs000001", "c1"),
        ("phs000002", "c1"),
        ("phs000001", "c2"),
    ]
    .into_iter()
    .enumerate()
    {
        let patient = base + offset as u32;
        let consent = format!("{study}.{group}");
        rows.push(text(patient, "\\_consents\\", &consent));
        rows.push(text(patient, "\\_studies\\", study));
        rows.push(text(patient, "\\_studies_consents\\", &consent));
        rows.push(text(
            patient,
            &format!("\\_studies_consents\\{study}\\"),
            &consent,
        ));
        rows.push(text(
            patient,
            &format!("\\_studies_consents\\{study}\\{group}\\"),
            &consent,
        ));
    }
    rows
}

/// Rollup reads TVAL_CHAR even on a numeric row: a numeric row whose text matches the last
/// path node is rolled up (LowRAM only), and its numeric value is what is stored.
fn rollup_on_numeric_row(base: u32) -> Vec<Record> {
    vec![
        full(base, "\\edge\\rollup\\numeric\\Yes\\", "5", "Yes", ""),
        full(base + 1, "\\edge\\rollup\\numeric\\Yes\\", "6", "No", ""),
        full(base + 2, "\\edge\\rollup\\numeric\\Yes\\", "7", "", ""),
    ]
}

/// Java `String.trim` strips every character up to U+0020, controls included, and nothing
/// else, while Rust `str::trim` strips Unicode white space. So U+0001 is trimmed from values
/// and path nodes by Java only, and U+00A0 and U+2003 by Rust only.
///
/// Java stores the text `\u{A0}` and the padded `\u{2003}em\u{2003}`, drops `\u{1}`, loads
/// `\u{1}42\u{1}` as 42, and merges `\u{1}ctrl_node\u{1}` into `ctrl_node`. An NVAL_NUM of
/// U+0001 is not blank to `Strings.isBlank`, which uses `Character.isWhitespace`, so its row is
/// numeric, empty after trimming, and rejected, after fixing its concept as numeric.
///
/// In the LowRAM loader `\edge\jtrim\control_nval\` sorts between the two raw spellings of
/// `ctrl_node`, so its one-concept cache keeps only the value 2 there. `CSVLoaderNewSearch`
/// keeps both, and Rust deliberately keeps both in every mode.
fn java_trim(base: u32) -> Vec<Record> {
    vec![
        numeric(base, "\\edge\\jtrim\\num\\", "\u{1}42\u{1}"),
        numeric(base + 1, "\\edge\\jtrim\\num\\", "43"),
        text(base, "\\edge\\jtrim\\txt\\", "\u{A0}"),
        text(base + 1, "\\edge\\jtrim\\txt\\", "\u{2003}em\u{2003}"),
        text(base + 2, "\\edge\\jtrim\\txt\\", "\u{1}ctrl\u{1}"),
        text(base + 3, "\\edge\\jtrim\\txt\\", "\u{1}"),
        full(
            base,
            "\\edge\\jtrim\\control_nval\\",
            "\u{1}",
            "control_text",
            "",
        ),
        numeric(base + 1, "\\edge\\jtrim\\control_nval\\", "3"),
        text(base + 2, "\\edge\\jtrim\\control_nval\\", "dropped"),
        numeric(base, "\\edge\\jtrim\\\u{1}ctrl_node\u{1}\\", "1"),
        numeric(base + 1, "\\edge\\jtrim\\ctrl_node\\", "2"),
        numeric(base, "\\edge\\jtrim\\\u{A0}nbsp_node\\", "1"),
        numeric(base + 1, "\\edge\\jtrim\\\u{2003}em_node\u{2003}\\", "2"),
    ]
}

/// Unicode white space in NVAL_NUM that Java does not trim. `\u{2003}` alone is blank to
/// `Strings.isBlank`, so the LowRAM loader reads the row as text, and `42\u{A0}` fails
/// `Double.parseDouble` and is rejected. LowRAM only: `CSVLoaderNewSearch` treats both as
/// numbers and throws.
fn java_unicode_space_numbers(base: u32) -> Vec<Record> {
    vec![
        full(base, "\\edge\\jtrim\\em_nval\\", "\u{2003}", "em_text", ""),
        full(base + 1, "\\edge\\jtrim\\em_nval\\", "", "plain_text", ""),
        numeric(base, "\\edge\\jtrim\\nbsp_num\\", "1"),
        numeric(base + 1, "\\edge\\jtrim\\nbsp_num\\", "42\u{A0}"),
    ]
}

/// Degenerate paths: an empty CONCEPT_PATH, a lone backslash and a lone `µ` all normalize to
/// the root path `\`, which both loaders accept as a concept.
///
/// The LowRAM loader sorts the raw paths apart (`µ` after everything, a leading space between
/// the empty path and `\`), so its one-concept cache keeps only the `µ` row, value 3.
/// `CSVLoaderNewSearch` keeps all three, and Rust deliberately keeps all three in every mode.
fn root_paths(base: u32) -> Vec<Record> {
    vec![
        numeric(base, "", "1"),
        numeric(base + 1, "\\", "2"),
        numeric(base + 2, "µ", "3"),
    ]
}

/// Rows with six and seven columns: the extra columns are ignored.
fn wide_rows(base: u32) -> Vec<Record> {
    let mut six = numeric(base, "\\edge\\wide\\var_a\\", "1");
    six.push("extra".to_string());
    let mut seven = full(base + 1, "\\edge\\wide\\var_a\\", "2", "", "1000");
    seven.push("extra".to_string());
    seven.push(String::new());
    vec![six, seven]
}

/// One patient whose rows of one concept are not contiguous: concept `a`, then `b`, then `a`
/// again. `CSVLoaderNewSearch` keeps file order within the patient; the LowRAM loader's stable
/// sort by path brings the two `a` rows together first.
fn interleaved_patient_rows(base: u32) -> Vec<Record> {
    vec![
        full(base, "\\edge\\interleave\\a\\", "1", "", "3000"),
        text(base, "\\edge\\interleave\\b\\", "between"),
        full(base, "\\edge\\interleave\\a\\", "2", "", "1000"),
        full(base + 1, "\\edge\\interleave\\a\\", "3", "", "2000"),
    ]
}

/// Sibling concept paths that order differently by UTF-16 code unit (Java `TreeMap`, so
/// `columnMeta.csv` order) and by code point (Rust `str`): U+1F600 is a surrogate pair that
/// sorts before U+FF5E in UTF-16 and after it by code point.
fn utf16_sibling_paths(base: u32) -> Vec<Record> {
    vec![
        numeric(base, "\\edge\\utf16_sibling\\\u{FF5E}\\", "1"),
        numeric(base + 1, "\\edge\\utf16_sibling\\\u{1F600}\\", "2"),
        numeric(base + 2, "\\edge\\utf16_sibling\\z\\", "3"),
    ]
}

/// A numeric concept whose values are all negative. Java starts its max at
/// `Double.MIN_VALUE`, the smallest positive double, so it reports a max of `4.9E-324`; Rust
/// reports the real max, -1.5.
fn all_negative(base: u32) -> Vec<Record> {
    vec![
        numeric(base, "\\edge\\num\\all_negative\\", "-3"),
        numeric(base + 1, "\\edge\\num\\all_negative\\", "-1.5"),
        numeric(base + 2, "\\edge\\num\\all_negative\\", "-100"),
    ]
}

type Case = fn(u32) -> Vec<Record>;

/// Cases every Java loader gets through.
const CORE_CASES: &[Case] = &[
    baseline,
    mu_in_path,
    path_node_spaces,
    missing_trailing_backslash,
    empty_path_nodes,
    replacement_char,
    rollup,
    numeric_then_text,
    text_then_numeric,
    type_fixed_by_rejected_blank,
    java_double_literals,
    hex_floats,
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
    repeated_observations,
    consent_concepts,
    rollup_on_numeric_row,
    java_trim,
    root_paths,
    wide_rows,
    interleaved_patient_rows,
    utf16_sibling_paths,
    all_negative,
];

/// Cases only the LowRAM loader gets through, because `CSVLoaderNewSearch` throws on them.
const LOWRAM_CASES: &[Case] = &[
    type_fixed_by_rejected_numeric,
    unparseable_numbers,
    bad_timestamps,
    only_rejected_rows,
    java_unicode_space_numbers,
];

const PATIENTS_PER_CASE: u32 = 20;
const FIRST_PATIENT: u32 = 1000;

fn write_cases<W: Write>(sink: W, groups: &[&[Case]]) -> csv::Result<usize> {
    let mut out = csv::WriterBuilder::new().flexible(true).from_writer(sink);
    out.write_record(HEADER)?;
    let mut count = 0;
    for (index, case) in groups.iter().flat_map(|group| group.iter()).enumerate() {
        let base = FIRST_PATIENT + index as u32 * PATIENTS_PER_CASE;
        for record in case(base) {
            out.write_record(&record)?;
            count += 1;
        }
    }
    out.flush()?;
    Ok(count)
}

/// Writes the core edge-case file, the cases every Java loader gets through, and returns the
/// number of records after the header.
///
/// Each case gets its own block of 20 patient IDs starting at 1000, so patients never collide
/// between cases. No negative patient IDs are written, see [`write_negative_ids`].
///
/// # Errors
/// Returns any I/O or CSV error from the sink.
pub fn write_core<W: Write>(sink: W) -> csv::Result<usize> {
    write_cases(sink, &[CORE_CASES])
}

/// Writes the LowRAM edge-case file, the core rows followed by the cases only the LowRAM loader
/// gets through, and returns the number of records after the header. Patient IDs continue the
/// core numbering, so the core rows are identical in both files.
///
/// # Errors
/// Returns any I/O or CSV error from the sink.
pub fn write_lowram<W: Write>(sink: W) -> csv::Result<usize> {
    write_cases(sink, &[CORE_CASES, LOWRAM_CASES])
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

/// Dataset name the config case prefixes every path with.
pub const CONFIG_DATASET_NAME: &str = "edge_dataset";

/// The `config.json` of the config case.
///
/// Both Java loaders read it from `/opt/local/hpds_input/config.json`, keyed by the CSV file
/// name without `.csv`: `CSVLoaderNewSearch` looks up `allConcepts`, and the LowRAM loader
/// looks up the file name `allConcepts.csv` with the extension stripped.
pub const CONFIG_JSON: &str = "{\"allConcepts\": {\"dataset_name\": \"edge_dataset\", \"dataset_name_as_root_node\": true}}\n";

/// Writes the CSV of the config case: paths with and without a leading backslash, with `µ`,
/// and one the LowRAM loader rolls up before the dataset name is prefixed.
///
/// # Errors
/// Returns any I/O or CSV error from the sink.
pub fn write_config_csv<W: Write>(sink: W) -> csv::Result<()> {
    let mut out = csv::WriterBuilder::new().from_writer(sink);
    out.write_record(HEADER)?;
    out.write_record(["1", "\\edge\\config\\var_a\\", "1", "", ""])?;
    out.write_record(["2", "edge\\config\\var_b", "", "no_leading_slash", ""])?;
    out.write_record(["3", "µedgeµconfigµvar_c", "2", "", ""])?;
    out.write_record(["4", "\\edge\\config\\question\\Yes\\", "", "Yes", ""])?;
    out.write_record(["5", "\\_consents\\", "", "phs000001.c1", ""])?;
    out.flush()?;
    Ok(())
}

fn write_file(
    path: &Path,
    write: impl FnOnce(io::BufWriter<fs::File>) -> csv::Result<()>,
) -> io::Result<()> {
    if let Some(parent) = path.parent() {
        fs::create_dir_all(parent)?;
    }
    write(io::BufWriter::new(fs::File::create(path)?)).map_err(io::Error::from)
}

/// Writes every edge-case dataset under `out_dir`, one directory per dataset with an
/// `allConcepts.csv` each, and returns the paths written:
///
/// - `core/`: rows every Java loader gets through ([`write_core`]).
/// - `lowram/`: the core rows plus the rows only the LowRAM loader gets through
///   ([`write_lowram`]).
/// - `negative-ids/`: [`write_negative_ids`].
/// - `embedded-newline/`: [`write_embedded_newline`].
/// - `config/`: [`write_config_csv`] plus `config.json` ([`CONFIG_JSON`]).
///
/// # Errors
/// Returns any error creating the directories or writing the files.
pub fn write_edge_dirs(out_dir: &Path) -> io::Result<Vec<PathBuf>> {
    let csv_in = |dir: &str| out_dir.join(dir).join("allConcepts.csv");
    let mut written = Vec::new();
    let core = csv_in("core");
    write_file(&core, |sink| write_core(sink).map(drop))?;
    written.push(core);
    let lowram = csv_in("lowram");
    write_file(&lowram, |sink| write_lowram(sink).map(drop))?;
    written.push(lowram);
    let negative = csv_in("negative-ids");
    write_file(&negative, write_negative_ids)?;
    written.push(negative);
    let newline = csv_in("embedded-newline");
    write_file(&newline, write_embedded_newline)?;
    written.push(newline);
    let config = csv_in("config");
    write_file(&config, write_config_csv)?;
    written.push(config);
    let json = out_dir.join("config").join("config.json");
    fs::write(&json, CONFIG_JSON)?;
    written.push(json);
    Ok(written)
}
