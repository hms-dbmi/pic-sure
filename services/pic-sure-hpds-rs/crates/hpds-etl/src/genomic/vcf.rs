//! Reading VCF index files and VCFs as the Java genomic loaders read them
//! (`VcfIndexFileParser`, `NewVCFLoader.VCFWalker`, `VcfInputFile`), with the inputs that
//! make Java store corrupt data refused, naming the file and line.
//!
//! The rules reproduced here, from the Java source:
//!
//! - Index files are tab-separated with quotes, the first record a header; a chromosome of
//!   `ALL` means none, and lines are ordered by chromosome, then VCF path.
//! - A VCF header must start its column line with the nine fixed columns and at least one
//!   sample. `##INFO` lines of an annotated VCF declare INFO columns: the line without
//!   `##INFO=<` and every `>`, split on `,`; the key is the text after the first `=` of the
//!   first field, the description the fourth field onward joined by `,`.
//! - A record's spec is `CHROM,POS,REF,ALT,gene,consequence`, gene and consequence taken
//!   from INFO split on `[=;]` and read in pairs, `NULL` when absent.
//! - A genotype is the first three characters of a sample field; its class is the sum of
//!   their character codes modulo 7.

use std::fs::File;
use std::io::{BufRead, BufReader, Read};
use std::path::{Path, PathBuf};

use flate2::read::MultiGzDecoder;
use hpds_format::{is_short_spec, java_parse_int, java_string_cmp};

use crate::error::{EtlError, Result};

/// The columns every VCF column line starts with, as `VCFWalker.readHeaders` checks them.
const VCF_FIXED_COLUMNS: &str = "#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\tFORMAT\t";

/// The INFO key whose value is the spec's gene.
const GENE_KEY: &str = "Gene_with_variant";

/// The INFO key whose value is the spec's consequence.
const CONSEQUENCE_KEY: &str = "Variant_consequence_calculated";

/// One line of a VCF index file.
#[derive(Clone, Debug)]
pub struct IndexLine {
    /// The VCF path as the index gives it, trimmed.
    pub vcf: PathBuf,
    /// The chromosome column, `None` for `ALL`. Java uses it only to order lines.
    pub contig: Option<String>,
    /// The isAnnotated flag: INFO columns and metadata are read from the VCF.
    pub annotated: bool,
    /// The isGzipped flag.
    pub gzipped: bool,
    /// Sample ids, parallel to `patients`.
    pub samples: Vec<String>,
    /// HPDS patient ids.
    pub patients: Vec<u32>,
    /// The line's 1-based line number in the index file.
    pub line: u64,
}

/// Builds the refusal of an input file at a line.
pub fn refuse(path: &Path, line: u64, detail: impl Into<String>) -> EtlError {
    EtlError::Input {
        path: path.to_path_buf(),
        line,
        detail: detail.into(),
    }
}

/// Reads an index file as `VcfIndexFileParser` does, in Java's line order: by chromosome
/// (`ALL` lines after the others), then by VCF path.
///
/// # Errors
/// [`EtlError::Input`] naming the line for a missing column, a flag that is not an `int`, a
/// patient id that is not an integer from 0 to 2147483647, sample and patient lists of
/// different lengths, or a VCF path listed twice; I/O errors.
pub fn read_index(path: &Path) -> Result<Vec<IndexLine>> {
    let mut reader = csv::ReaderBuilder::new()
        .delimiter(b'\t')
        .has_headers(false)
        .flexible(true)
        .from_path(path)
        .map_err(|source| EtlError::Csv {
            path: path.to_path_buf(),
            source,
        })?;
    let mut lines = Vec::new();
    let mut first = true;
    for record in reader.records() {
        let record = record.map_err(|source| EtlError::Csv {
            path: path.to_path_buf(),
            source,
        })?;
        let line = record.position().map_or(0, |p| p.line());
        if std::mem::take(&mut first) {
            continue;
        }
        let field = |i: usize, name: &str| {
            record
                .get(i)
                .map(|f| f.trim_matches(|c: char| c <= ' '))
                .ok_or_else(|| refuse(path, line, format!("no {name} column")))
        };
        let flag = |i: usize, name: &str| -> Result<bool> {
            let text = field(i, name)?;
            java_parse_int(text)
                .map(|v| v == 1)
                .ok_or_else(|| refuse(path, line, format!("the {name} flag is not an integer")))
        };
        let contig = field(1, "chromosome")?;
        let samples: Vec<String> = java_split(field(4, "sample ids")?, |c| c == ',')
            .into_iter()
            .map(|s| s.trim_matches(|c: char| c <= ' ').to_string())
            .collect();
        let mut patients = Vec::new();
        for (i, text) in java_split(field(5, "patient ids")?, |c| c == ',')
            .into_iter()
            .enumerate()
        {
            let id = java_parse_int(text.trim_matches(|c: char| c <= ' '))
                .and_then(|id| u32::try_from(id).ok())
                .ok_or_else(|| {
                    refuse(
                        path,
                        line,
                        format!("patient id {} is not an integer from 0 to 2147483647", i + 1),
                    )
                })?;
            patients.push(id);
        }
        if samples.len() != patients.len() {
            return Err(refuse(
                path,
                line,
                format!(
                    "{} sample ids and {} patient ids; Java pairs them by position and fails on a mismatch",
                    samples.len(),
                    patients.len()
                ),
            ));
        }
        lines.push(IndexLine {
            vcf: PathBuf::from(field(0, "VCF path")?),
            contig: (contig != "ALL").then(|| contig.to_string()),
            annotated: flag(2, "isAnnotated")?,
            gzipped: flag(3, "isGzipped")?,
            samples,
            patients,
            line,
        });
    }
    for (i, a) in lines.iter().enumerate() {
        if let Some(b) = lines[..i].iter().find(|b| b.vcf == a.vcf) {
            return Err(refuse(
                path,
                a.line,
                format!(
                    "VCF {} is listed again (first on line {}); Java would load it into one node twice or drop the line",
                    a.vcf.display(),
                    b.line
                ),
            ));
        }
    }
    lines.sort_by(|a, b| {
        let contigs = match (&a.contig, &b.contig) {
            (Some(x), Some(y)) => java_string_cmp(x, y),
            (x, y) => y.is_none().cmp(&x.is_none()).reverse(),
        };
        contigs.then_with(|| {
            java_string_cmp(&a.vcf.to_string_lossy(), &b.vcf.to_string_lossy())
        })
    });
    Ok(lines)
}

/// Reads a VCF line by line, gzip (BGZF or plain gzip) or plain, as UTF-8, with `\n`, `\r\n`
/// line ends.
pub struct VcfLines {
    path: PathBuf,
    reader: Box<dyn BufRead + Send>,
    buf: Vec<u8>,
    line: u64,
}

impl VcfLines {
    /// Opens `path`, decompressing when `gzipped`.
    ///
    /// # Errors
    /// I/O errors opening the file.
    pub fn open(path: &Path, gzipped: bool) -> Result<Self> {
        let file = File::open(path).map_err(EtlError::io("opening", path))?;
        let inner: Box<dyn Read + Send> = if gzipped {
            Box::new(MultiGzDecoder::new(BufReader::with_capacity(1 << 20, file)))
        } else {
            Box::new(file)
        };
        Ok(VcfLines {
            path: path.to_path_buf(),
            reader: Box::new(BufReader::with_capacity(1 << 20, inner)),
            buf: Vec::with_capacity(1 << 16),
            line: 0,
        })
    }

    /// The file being read.
    pub fn path(&self) -> &Path {
        &self.path
    }

    /// The next line without its line end and its 1-based line number, or `None` at the
    /// end of the file.
    ///
    /// # Errors
    /// I/O and decompression errors, and [`EtlError::Input`] for a line that is not UTF-8.
    pub fn next_line(&mut self) -> Result<Option<(u64, &str)>> {
        self.buf.clear();
        let n = self
            .reader
            .read_until(b'\n', &mut self.buf)
            .map_err(EtlError::io("reading", &self.path))?;
        if n == 0 {
            return Ok(None);
        }
        self.line += 1;
        if self.buf.last() == Some(&b'\n') {
            self.buf.pop();
            if self.buf.last() == Some(&b'\r') {
                self.buf.pop();
            }
        }
        std::str::from_utf8(&self.buf)
            .map(|text| Some((self.line, text)))
            .map_err(|_| refuse(&self.path, self.line, "the line is not UTF-8"))
    }
}

/// An INFO column a VCF header declares.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct InfoDeclaration {
    /// The column key.
    pub key: String,
    /// The description as Java stores it, `Description="..."` with its prefix and quotes.
    pub description: String,
}

/// What a VCF's header holds.
#[derive(Clone, Debug)]
pub struct VcfHeader {
    /// The INFO columns declared, in header order (read only for annotated VCFs).
    pub info: Vec<InfoDeclaration>,
    /// The sample names of the column line, in order.
    pub samples: Vec<String>,
}

/// `String.split` with a one-character pattern: the fields, trailing empty ones removed, or
/// the string itself when it holds no separator.
pub fn java_split(s: &str, separator: impl Fn(char) -> bool + Copy) -> Vec<&str> {
    if !s.contains(separator) {
        return vec![s];
    }
    let mut fields: Vec<&str> = s.split(separator).collect();
    while fields.last().is_some_and(|f| f.is_empty()) {
        fields.pop();
    }
    fields
}

/// Parses one `##INFO` line as `readHeaders` does.
fn info_declaration(line: &str) -> std::result::Result<InfoDeclaration, String> {
    let stripped = line.replace("##INFO=<", "").replace('>', "");
    let fields = java_split(&stripped, |c| c == ',');
    if fields.len() < 4 {
        return Err(format!(
            "the INFO header line has {} comma-separated fields, where Java reads a fourth",
            fields.len()
        ));
    }
    let id = java_split(fields[0], |c| c == '=');
    let key = id
        .get(1)
        .ok_or_else(|| "the INFO header line has no key after ID=".to_string())?;
    Ok(InfoDeclaration {
        key: (*key).to_string(),
        description: fields[3..].join(","),
    })
}

/// Reads the header of a VCF, leaving `lines` at the column line. INFO declarations are read
/// only when `annotated`.
///
/// # Errors
/// [`EtlError::Input`] for an INFO line Java cannot parse, a missing or malformed column
/// line, or a column line without samples; read errors.
pub fn read_header(lines: &mut VcfLines, annotated: bool) -> Result<VcfHeader> {
    let mut info = Vec::new();
    let path = lines.path().to_path_buf();
    loop {
        let Some((line_no, line)) = lines.next_line()? else {
            return Err(refuse(&path, 0, "the file ends before the #CHROM line"));
        };
        if line.starts_with("##") {
            if annotated && line.starts_with("##INFO") {
                let declaration =
                    info_declaration(line).map_err(|detail| refuse(&path, line_no, detail))?;
                info.push(declaration);
            }
            continue;
        }
        if !line.starts_with(VCF_FIXED_COLUMNS) {
            return Err(refuse(
                &path,
                line_no,
                "the column line does not start with the nine fixed VCF columns and a sample",
            ));
        }
        let samples: Vec<String> = java_split(line, |c| c == '\t')[9..]
            .iter()
            .map(|s| (*s).to_string())
            .collect();
        if samples.is_empty() {
            return Err(refuse(&path, line_no, "the column line names no sample"));
        }
        return Ok(VcfHeader { info, samples });
    }
}

/// The fixed fields of a data record and its sample text.
#[derive(Clone, Copy, Debug)]
pub struct Record<'a> {
    /// CHROM.
    pub contig: &'a str,
    /// POS as written.
    pub pos: &'a str,
    /// POS as a Java `int`.
    pub position: i32,
    /// REF.
    pub reference: &'a str,
    /// ALT.
    pub alt: &'a str,
    /// INFO.
    pub info: &'a str,
    /// FORMAT.
    pub format: &'a str,
    /// Everything after the FORMAT field's tab: the sample fields.
    pub samples: &'a str,
}

/// Splits a data record and checks what both Java loaders need of it: at least ten
/// tab-separated fields, no fixed field starting with a quote (the metadata loader reads the
/// VCF as quoted CSV), a CHROM and REF without commas, a biallelic ALT, and a POS that is a
/// Java `int` from 0 written as Java writes it back (the metadata loader re-renders it).
///
/// # Errors
/// The refusal's text.
pub fn parse_record(line: &str) -> std::result::Result<Record<'_>, String> {
    let mut fields = [""; 9];
    let mut rest = line;
    for (i, slot) in fields.iter_mut().enumerate() {
        let (field, tail) = rest
            .split_once('\t')
            .ok_or_else(|| format!("the record has {} tab-separated fields, fewer than ten", i + 1))?;
        *slot = field;
        rest = tail;
    }
    if let Some(i) = fields[..8].iter().position(|f| f.starts_with('"')) {
        return Err(format!(
            "field {} starts with a quote, which the metadata loader reads as quoted CSV",
            i + 1
        ));
    }
    let [contig, pos, _, reference, alt, _, _, info, format] = fields;
    if contig.contains(',') {
        return Err(format!("CHROM {contig:?} holds a comma, which breaks the spec"));
    }
    if reference.contains(',') {
        return Err("REF holds a comma, which breaks the spec".into());
    }
    if alt.contains(',') {
        return Err(format!(
            "ALT {alt:?} is multi-allelic; Java stores one spec for all its alleles"
        ));
    }
    let position = java_parse_int(pos)
        .filter(|&p| p >= 0 && p.to_string() == pos)
        .ok_or_else(|| {
            format!(
                "POS {pos:?} is not a non-negative Java int written without sign or leading zeros; the two Java loaders would store different specs for it"
            )
        })?;
    Ok(Record {
        contig,
        pos,
        position,
        reference,
        alt,
        info,
        format,
        samples: rest,
    })
}

/// The spec Java builds for a record: `CHROM,POS,REF,ALT,gene,consequence`, gene and
/// consequence read from INFO split on `[=;]` in pairs.
///
/// # Errors
/// The refusal's text when the gene or consequence key is the last token (Java reads past
/// the array and fails), or the spec has fewer than six fields after Java's split.
pub fn spec_notation(record: &Record<'_>) -> std::result::Result<String, String> {
    let tokens = java_split(record.info, |c| c == '=' || c == ';');
    let mut gene = "NULL";
    let mut consequence = "NULL";
    let mut i = 0;
    while i < tokens.len() {
        for (key, slot) in [(GENE_KEY, &mut gene), (CONSEQUENCE_KEY, &mut consequence)] {
            if tokens[i] == key {
                *slot = tokens.get(i + 1).ok_or_else(|| {
                    format!("INFO ends with the key {key} and no value; Java reads past it and fails")
                })?;
            }
        }
        i += 2;
    }
    let spec = format!(
        "{},{},{},{},{gene},{consequence}",
        record.contig, record.pos, record.reference, record.alt
    );
    if is_short_spec(&spec) {
        return Err(format!(
            "spec {spec:?} has fewer than six fields after Java's split; Java cannot parse it back"
        ));
    }
    Ok(spec)
}

/// The class of a genotype's first three characters, `(c0 + c1 + c2) % 7`, when they are a
/// biallelic diploid call: `0`, `1` or `.` on each side of `/` or `|`. Class 1 is `./0`,
/// 2 `./1` (`hen`), 3 `0/0`, 4 `0/1` (`he`), 5 `1/1` (`ho`), 6 `./.` (`hon`).
pub fn genotype_class(gt: &[u8]) -> Option<u8> {
    let allele = |c: u8| matches!(c, b'0' | b'1' | b'.');
    match gt {
        [a, s, b] if allele(*a) && matches!(s, b'/' | b'|') && allele(*b) => {
            Some(((u32::from(*a) + u32::from(*s) + u32::from(*b)) % 7) as u8)
        }
        _ => None,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn record(info: &str) -> String {
        format!("chr1\t100\t.\tA\tG\t.\tPASS\t{info}\tGT\t0|1")
    }

    #[test]
    fn genotype_classes_follow_the_java_table() {
        let cases = [
            ("0/0", 3),
            ("0|0", 3),
            ("0/1", 4),
            ("1/0", 4),
            ("0|1", 4),
            ("1|0", 4),
            ("1/1", 5),
            ("1|1", 5),
            ("./.", 6),
            (".|.", 6),
            ("./1", 2),
            ("1/.", 2),
            (".|1", 2),
            ("1|.", 2),
            ("./0", 1),
            ("0/.", 1),
            (".|0", 1),
        ];
        for (gt, class) in cases {
            assert_eq!(genotype_class(gt.as_bytes()), Some(class), "{gt}");
        }
        for gt in ["0/2", "1/2", "2/2", "0", "0/1/1", "0-1", "a/b", ""] {
            assert_eq!(genotype_class(gt.as_bytes()), None, "{gt}");
        }
    }

    #[test]
    fn spec_pairs_info_tokens_as_java_does() {
        let line = record("Gene_with_variant=G;Variant_consequence_calculated=c");
        let r = parse_record(&line).unwrap();
        assert_eq!(spec_notation(&r).unwrap(), "chr1,100,A,G,G,c");
        let line = record("FLAG;Gene_with_variant=G;Variant_consequence_calculated=c");
        let r = parse_record(&line).unwrap();
        assert_eq!(spec_notation(&r).unwrap(), "chr1,100,A,G,NULL,NULL");
        let line = record("Gene_with_variant=A&B;X=1");
        let r = parse_record(&line).unwrap();
        assert_eq!(spec_notation(&r).unwrap(), "chr1,100,A,G,A&B,NULL");
        let line = record(".");
        assert_eq!(
            spec_notation(&parse_record(&line).unwrap()).unwrap(),
            "chr1,100,A,G,NULL,NULL"
        );
    }

    #[test]
    fn spec_refusals() {
        let line = record("X=1;Gene_with_variant");
        assert!(spec_notation(&parse_record(&line).unwrap()).is_err());
        let line = record("Gene_with_variant=g;Variant_consequence_calculated=");
        assert!(spec_notation(&parse_record(&line).unwrap()).is_err());
        let line = record("Gene_with_variant=;Variant_consequence_calculated=c");
        assert_eq!(
            spec_notation(&parse_record(&line).unwrap()).unwrap(),
            "chr1,100,A,G,,c"
        );
    }

    #[test]
    fn record_refusals() {
        assert!(parse_record("chr1\t100\t.\tA\tG,T\t.\tPASS\t.\tGT\t0|1").is_err());
        assert!(parse_record("chr1\t0100\t.\tA\tG\t.\tPASS\t.\tGT\t0|1").is_err());
        assert!(parse_record("chr1\t+100\t.\tA\tG\t.\tPASS\t.\tGT\t0|1").is_err());
        assert!(parse_record("chr1\t-1\t.\tA\tG\t.\tPASS\t.\tGT\t0|1").is_err());
        assert!(parse_record("chr1\tx\t.\tA\tG\t.\tPASS\t.\tGT\t0|1").is_err());
        assert!(parse_record("chr1\t100\t.\tA\tG\t.\tPASS\t.\tGT").is_err());
        assert!(parse_record("c,1\t100\t.\tA\tG\t.\tPASS\t.\tGT\t0|1").is_err());
        assert!(parse_record("chr1\t100\t.\tA\tG\t.\tPASS\t\"x\tGT\t0|1").is_err());
        let r = parse_record("chr1\t0\t.\tA\tG\t.\tPASS\t.\tGT\t0|1\t1|1").unwrap();
        assert_eq!(r.samples, "0|1\t1|1");
        assert_eq!(r.position, 0);
    }

    #[test]
    fn info_declarations_parse_as_java_does() {
        let d = info_declaration(
            "##INFO=<ID=Gene_with_variant,Number=.,Type=String,Description=\"a, b > c\">",
        )
        .unwrap();
        assert_eq!(d.key, "Gene_with_variant");
        assert_eq!(d.description, "Description=\"a, b  c\"");
        assert!(info_declaration("##INFO=<ID=X,Number=0>").is_err());
        assert!(info_declaration("##INFO=<ID,Number=0,Type=Flag,Description=\"x\">").is_err());
    }
}
