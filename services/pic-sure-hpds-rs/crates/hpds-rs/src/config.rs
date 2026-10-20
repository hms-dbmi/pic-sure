//! Service settings read from the environment.

use std::path::PathBuf;
use std::time::Duration;

use hpds_format::GenoMode;
use hpds_query::{
    DEFAULT_CACHE_BYTES, DEFAULT_DATA_DIRECTORY, DEFAULT_GENOMIC_CACHE_BYTES,
    DEFAULT_GENOMIC_READ_BUDGET_BYTES, DEFAULT_MAX_CLAUSE_DEPTH, DEFAULT_MAX_CLAUSE_NODES,
    DEFAULT_MAX_CROSS_COUNT_ENTRIES, DEFAULT_MAX_EXCERPT_BYTES, DEFAULT_MAX_QUERY_CONCEPTS,
    DEFAULT_MAX_SELECT_PATHS, DEFAULT_PREFIX_READ_BUDGET_BYTES, DEFAULT_PREFIX_READ_BYTES,
    GenomicOptions, PartitionOptions, QueryLimits, StoreOptions, VariantResultSettings,
};

/// Genomic data directory used when `HPDS_GENOMIC_DATA_DIRECTORY` is not set, Java's default.
pub const DEFAULT_GENOMIC_DATA_DIRECTORY: &str = "/opt/local/hpds/all/";

/// The genomic settings.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct GenomicConfig {
    /// `HPDS_GENOMIC_DATA_DIRECTORY`: the directory holding `geno.index` and `geno.blocks`.
    pub data_dir: PathBuf,
    /// `hpds.genomicProcessor.impl`: the layout the store must have, or `None` when unset,
    /// which serves no genomic data, as Java's no-op processor does.
    pub processor: Option<GenoMode>,
    /// The genomic cache and read budget; the pool is sized like the main one.
    pub options: GenomicOptions,
    /// The variant result settings: the excerpt gates, `ID_CUBE_NAME`,
    /// `variant.info_column_order` and `HPDS_MAX_EXCERPT_BYTES`.
    pub variant: VariantResultSettings,
}

/// Port used when `SERVER_PORT` is not set.
pub const DEFAULT_PORT: u16 = 8080;

/// Directory for export result files when `HPDS_RESULTS_DIRECTORY` is not set.
pub const DEFAULT_RESULTS_DIRECTORY: &str = "/tmp/hpds-rs-results";

/// Largest request body accepted, in bytes.
pub const MAX_BODY_BYTES: usize = 2 << 20;

/// Default byte cap on the decoded blocks one DATAFRAME export holds, 2 GiB.
pub const DEFAULT_EXPORT_PIN_BYTES: u64 = 2 << 30;

/// Default byte cap on the finished export files held at once, 10 GiB.
pub const DEFAULT_RESULT_MAX_BYTES: u64 = 10 << 30;

/// Default byte cap on one export file while it is written, 4 GiB.
pub const DEFAULT_RESULT_MAX_FILE_BYTES: u64 = 4 << 30;

/// Default byte budget of the export results held in memory instead of in files, 64 MiB.
pub const DEFAULT_RESULT_MEMORY_BYTES: u64 = 64 << 20;

/// Default seconds a sync query may run before the request is answered 503.
pub const DEFAULT_SYNC_TIMEOUT_SECONDS: u64 = 300;

/// Default seconds a streamed excerpt waits for its client to take the next chunk.
pub const DEFAULT_EXCERPT_STALL_SECONDS: u64 = 30;

/// Default seconds a streamed excerpt's body may take to be sent in full.
pub const DEFAULT_EXCERPT_STREAM_SECONDS: u64 = 600;

/// Default seconds an excerpt or variant list waits for an excerpt slot before it is a 503.
pub const DEFAULT_EXCERPT_QUEUE_SECONDS: u64 = 5;

/// Default seconds a write to a connection may stay pending before the connection is closed.
pub const DEFAULT_WRITE_STALL_SECONDS: u64 = 60;

const DEFAULT_BLOCKING_THREADS: usize = 64;
const BLOCKING_THREAD_HEADROOM: usize = 16;

/// Every setting the service reads at startup.
///
/// | Variable | Default | Meaning |
/// |---|---|---|
/// | `HPDS_DATA_DIRECTORY` | `/opt/local/hpds/` | Data directory: one store, partitioned by consent or not |
/// | `SERVER_PORT` | 8080 | Listen port |
/// | `HPDS_CACHE_BYTES` (or the old name `CACHE_BYTES`, with a warning) | 4 GiB | Byte bound of the decoded block cache |
/// | `HPDS_RESULT_CACHE_BYTES` | 0 (off) | Byte bound of the cache of COUNT, CROSS_COUNT, CATEGORICAL_CROSS_COUNT, CONTINUOUS_CROSS_COUNT and OBSERVATION_CROSS_COUNT answers |
/// | `CACHE_SIZE` | ignored | Java's cache size in cubes; logged and ignored |
/// | `HPDS_ALLOW_PLAIN` | false | Open a store without a key file (benchmarks only) |
/// | `HPDS_REQUIRE_AUTHORIZATION_FILTER` or `hpds.requireAuthorizationFilter` | true | Refuse every sync and async query without a `userConsents` value, and refuse to open an unpartitioned data directory |
/// | `HPDS_COMBINED_STORE`, `HPDS_PER_PARTITION_COUNTS` | ignored | Settings of the previous store layout; logged and ignored |
/// | `HPDS_RESULTS_DIRECTORY` | `/tmp/hpds-rs-results` | Where export results are written |
/// | `HPDS_RESULT_LIMIT` | 1000 | Most async results held at once |
/// | `HPDS_RESULT_MAX_AGE_SECONDS` | unlimited | How long a finished result is kept; 0 or unset keeps it until evicted |
/// | `HPDS_RESULT_MAX_BYTES` | 10 GiB | Byte cap on finished export results, in files and in memory; the oldest finished result goes first |
/// | `HPDS_RESULT_MEMORY_BYTES` | 64 MiB | Budget of the export results of at most 256 KiB held in memory instead of in a file, downloads in progress included; a result that does not fit is written to a file; 0 writes every result to a file |
/// | `ID_BATCH_SIZE` | 0 | Java's export switch: 0 disables DATAFRAME exports, any larger value enables them |
/// | `HPDS_EXPORT_PIN_BYTES` | 2 GiB | Cap on the decoded blocks one DATAFRAME export holds; a wider export ends in ERROR |
/// | `HPDS_PREFIX_READ_BYTES` | 512 MiB | Bound on the decoded blocks one `ANY_RECORD_OF` holds outside the block cache; a prefix covering more is read in chunks within it |
/// | `HPDS_PREFIX_READ_BUDGET_BYTES` | 1 GiB | Bound on the decoded blocks every `ANY_RECORD_OF` of the process holds at once outside the block cache; a chunk that does not fit waits |
/// | `HPDS_RESULT_MAX_FILE_BYTES` | 4 GiB | Cap on one export file, checked as it is written; a larger export ends in ERROR and its partial file is deleted |
/// | `HPDS_MAX_SELECT_PATHS` | 5,000 | Most distinct `select` paths in a query |
/// | `HPDS_MAX_CLAUSE_DEPTH` | 64 | Deepest nesting of phenotypic subqueries |
/// | `HPDS_MAX_CLAUSE_NODES` | 10,000 | Most clauses and authorization filters in a query |
/// | `HPDS_MAX_QUERY_CONCEPTS` | 20,000 | Most concepts a query touches, `ANY_RECORD_OF` expansions included, counted over the union of the partitions its consents select |
/// | `HPDS_MAX_CROSS_COUNT_ENTRIES` | 5,000,000 | Most entries in a categorical or continuous cross count |
/// | `HPDS_SYNC_TIMEOUT_SECONDS` | 300 | A sync query running longer is answered 503 |
/// | `HPDS_LOG_DIRECTORY` | unset | When set, the log is also written to a daily-rotated file there |
/// | `HPDS_SEARCH_GZIP` | off | `on` compresses `/search` answers for requests that accept gzip |
/// | `HPDS_METRICS` | off | `on` serves `GET /metrics` in the Prometheus text format; off, that route is a 404 |
/// | `SMALL_JOB_LIMIT` | 100 | An export with more `select` paths than this runs on the large pool |
/// | `SMALL_TASK_THREADS` | 1 | Exports run at once on the small pool |
/// | `LARGE_TASK_THREADS` | 1 | Exports run at once on the large pool |
/// | `HPDS_EXPORT_CONCURRENCY` | 2 | Exports run at once across both pools; each pins its selected blocks |
/// | `HPDS_ENGINE_CONCURRENCY` | CPU count | Engine computations (sync queries and exports) run at once |
/// | `HPDS_MAX_CONNECTIONS` | 512 | Most connections served at once; a connection past the cap is answered 503 and closed |
/// | `HPDS_BLOCKING_THREADS` | 64, or the engine concurrency plus 16 when larger | Cap on tokio's blocking threads, which run every engine call; must exceed `HPDS_ENGINE_CONCURRENCY` |
/// | `hpds.genomicProcessor.impl` or `HPDS_GENOMICPROCESSOR_IMPL` | unset | Genomic layout: `local`, `localDistributed`, `localPatientDistributed` or `localPatientOnlyDistributed`, checked against the store's; unset serves no genomic data; `remote` is refused |
/// | `HPDS_EXCERPT_CONCURRENCY` | half the engine concurrency, at least 1 | Excerpts and variant lists computed or sent at once; must be below `HPDS_ENGINE_CONCURRENCY` when that is above 1, and 1 when it is 1 |
/// | `HPDS_EXCERPT_STALL_SECONDS` | 30 | How long a streamed excerpt waits for its client to take the next chunk before it ends the stream |
/// | `HPDS_EXCERPT_STREAM_SECONDS` | 600 | How long a streamed excerpt's body may take in full before the stream is ended |
/// | `HPDS_EXCERPT_QUEUE_SECONDS` | 5 | How long an excerpt or variant list waits for an excerpt slot before it is a 503 with `Retry-After`; 0 refuses at once |
/// | `HPDS_WRITE_STALL_SECONDS` | 60 | How long any write to a connection may stay pending, the client making no room in its socket buffer, before the connection is closed |
/// | `HPDS_GENOMIC_DATA_DIRECTORY` | `/opt/local/hpds/all/` | Directory of the Rust genomic store |
/// | `HPDS_GENOMIC_CACHE_BYTES` | 2 GiB | Byte bound of the decoded genomic block cache |
/// | `HPDS_GENOMIC_READ_BUDGET_BYTES` | 1 GiB | Bound on the genomic blocks every scan of the process holds at once outside the cache |
/// | `VCF_EXCERPT_ENABLED` | false | Allows VCF_EXCERPT, AGGREGATE_VCF_EXCERPT and VARIANT_LIST_FOR_QUERY |
/// | `AGGREGATE_VCF_EXCERPT_ENABLED` | false | Allows AGGREGATE_VCF_EXCERPT and VARIANT_LIST_FOR_QUERY |
/// | `ID_CUBE_NAME` | `NONE` | Concept whose value heads each VCF_EXCERPT patient column; `NONE` heads them with the patient id |
/// | `variant.info_column_order` or `VARIANT_INFO_COLUMN_ORDER` | empty | Comma-separated INFO columns of the excerpts, in order |
/// | `HPDS_MAX_EXCERPT_BYTES` | 1 GiB | Largest excerpt or variant list answered; a larger one is a 400 |
#[derive(Debug, Clone, PartialEq)]
pub struct Config {
    /// The data directory.
    pub data_dir: PathBuf,
    /// The listen port.
    pub port: u16,
    /// How the partitions are opened, with `require_consents` from
    /// `hpds.requireAuthorizationFilter`.
    pub partitions: PartitionOptions,
    /// Directory for export result files.
    pub results_dir: PathBuf,
    /// Most async results held at once.
    pub result_limit: usize,
    /// How long a finished result is kept, or `None` to keep it until evicted.
    pub result_max_age: Option<Duration>,
    /// Byte cap on finished export files held at once.
    pub result_max_bytes: u64,
    /// Java's `ID_BATCH_SIZE`; 0 disables DATAFRAME exports.
    pub id_batch_size: u64,
    /// Cap on the decoded blocks one DATAFRAME export holds.
    pub export_pin_bytes: u64,
    /// Cap on one export file while it is written.
    pub result_max_file_bytes: u64,
    /// Budget of the small export results held in memory; 0 writes every result to a file.
    pub result_memory_bytes: u64,
    /// Per-query bounds the engine enforces.
    pub query_limits: QueryLimits,
    /// How long a sync query may run before the request is answered 503.
    pub sync_timeout: Duration,
    /// Directory for the rotated log file, when set.
    pub log_directory: Option<PathBuf>,
    /// An export with more `select` paths than this runs on the large pool.
    pub small_job_limit: usize,
    /// Exports run at once on the small pool.
    pub small_task_threads: usize,
    /// Exports run at once on the large pool.
    pub large_task_threads: usize,
    /// Exports run at once across both pools.
    pub export_concurrency: usize,
    /// Engine computations run at once.
    pub engine_concurrency: usize,
    /// Cap on tokio's blocking threads.
    pub blocking_threads: usize,
    /// Most connections served at once.
    pub max_connections: usize,
    /// Byte bound of the sync result cache; 0 turns it off.
    pub result_cache_bytes: u64,
    /// Serve `GET /metrics` and count requests.
    pub metrics: bool,
    /// Compress `/search` answers for requests that accept gzip.
    pub search_gzip: bool,
    /// Excerpts computed or streamed at once.
    pub excerpt_concurrency: usize,
    /// How long a streamed excerpt waits for its client to take the next chunk.
    pub excerpt_stall: Duration,
    /// How long a streamed excerpt's body may take in full.
    pub excerpt_stream: Duration,
    /// How long an excerpt or variant list waits for an excerpt slot before it is a 503.
    pub excerpt_queue: Duration,
    /// How long a write to a connection may stay pending before the connection is closed.
    pub write_stall: Duration,
    /// The genomic settings.
    pub genomic: GenomicConfig,
    /// Settings that were read but have no effect, as messages for the log.
    pub warnings: Vec<String>,
}

impl Config {
    /// Reads the settings from the process environment.
    ///
    /// # Errors
    /// A message naming the first variable whose value cannot be parsed.
    pub fn from_env() -> Result<Self, String> {
        Self::from_lookup(|name| std::env::var(name).ok())
    }

    /// Reads the settings through `lookup`, which returns a variable's value or `None` when it
    /// is unset. Every variable falls back to the default in the table above.
    ///
    /// # Errors
    /// A message naming the first variable whose value cannot be parsed, or the pair of
    /// settings that conflict (`HPDS_ENGINE_CONCURRENCY` must be below `HPDS_BLOCKING_THREADS`).
    pub fn from_lookup(lookup: impl Fn(&str) -> Option<String>) -> Result<Self, String> {
        let mut warnings = Vec::new();
        for retired in ["HPDS_COMBINED_STORE", "HPDS_PER_PARTITION_COUNTS"] {
            if lookup(retired).is_some() {
                warnings.push(format!(
                    "{retired} belongs to the previous store layout and is ignored; every \
                     query now reads each concept's one block"
                ));
            }
        }
        if lookup("CACHE_SIZE").is_some() {
            warnings.push(
                "CACHE_SIZE counts cubes in the Java service and is ignored; set HPDS_CACHE_BYTES"
                    .to_owned(),
            );
        }
        let cache_bytes = match (lookup("HPDS_CACHE_BYTES"), lookup("CACHE_BYTES")) {
            (Some(_), Some(_)) => {
                warnings.push(
                    "CACHE_BYTES is the old name and is ignored; HPDS_CACHE_BYTES is set"
                        .to_owned(),
                );
                parse_or(&lookup, "HPDS_CACHE_BYTES", DEFAULT_CACHE_BYTES)?
            }
            (Some(_), None) => parse_or(&lookup, "HPDS_CACHE_BYTES", DEFAULT_CACHE_BYTES)?,
            (None, Some(_)) => {
                warnings
                    .push("CACHE_BYTES is deprecated; rename it to HPDS_CACHE_BYTES".to_owned());
                parse_or(&lookup, "CACHE_BYTES", DEFAULT_CACHE_BYTES)?
            }
            (None, None) => DEFAULT_CACHE_BYTES,
        };
        let result_cache_bytes = parse_or(&lookup, "HPDS_RESULT_CACHE_BYTES", 0)?;
        if result_cache_bytes > 0 && result_cache_bytes < hpds_query::MIN_RESULT_CACHE_BYTES {
            warnings.push(format!(
                "HPDS_RESULT_CACHE_BYTES={result_cache_bytes} is below {}, the smallest bound \
                 under which the result cache can store an answer (one answer may take at \
                 most a sixteenth of it), so the cache is on but stores nothing; set 0 to \
                 turn it off or a larger bound",
                hpds_query::MIN_RESULT_CACHE_BYTES
            ));
        }
        let engine_concurrency = positive(&lookup, "HPDS_ENGINE_CONCURRENCY", cpu_count())?;
        let blocking_threads = positive(
            &lookup,
            "HPDS_BLOCKING_THREADS",
            DEFAULT_BLOCKING_THREADS.max(engine_concurrency + BLOCKING_THREAD_HEADROOM),
        )?;
        if engine_concurrency >= blocking_threads {
            return Err(format!(
                "HPDS_ENGINE_CONCURRENCY ({engine_concurrency}) must be less than \
                 HPDS_BLOCKING_THREADS ({blocking_threads}); the difference is the headroom \
                 file reads need on tokio's blocking pool"
            ));
        }
        let excerpt_concurrency = positive(
            &lookup,
            "HPDS_EXCERPT_CONCURRENCY",
            (engine_concurrency / 2).max(1),
        )?;
        if engine_concurrency > 1 && excerpt_concurrency >= engine_concurrency {
            return Err(format!(
                "HPDS_EXCERPT_CONCURRENCY ({excerpt_concurrency}) must be less than \
                 HPDS_ENGINE_CONCURRENCY ({engine_concurrency}), so excerpts can never hold \
                 every engine slot"
            ));
        }
        if engine_concurrency == 1 && excerpt_concurrency > 1 {
            return Err(format!(
                "HPDS_EXCERPT_CONCURRENCY ({excerpt_concurrency}) must be 1 when \
                 HPDS_ENGINE_CONCURRENCY is 1"
            ));
        }
        if engine_concurrency == 1 {
            warnings.push(
                "HPDS_ENGINE_CONCURRENCY is 1, so one streaming excerpt holds the only engine \
                 slot until it is sent or its stall or stream bound ends it"
                    .to_owned(),
            );
        }
        let result_max_age = match parse_or(&lookup, "HPDS_RESULT_MAX_AGE_SECONDS", 0u64)? {
            0 => None,
            seconds => Some(Duration::from_secs(seconds)),
        };
        let require_consents = match lookup("HPDS_REQUIRE_AUTHORIZATION_FILTER") {
            Some(value) => parse_bool("HPDS_REQUIRE_AUTHORIZATION_FILTER", &value)?,
            None => match lookup("hpds.requireAuthorizationFilter") {
                Some(value) => parse_bool("hpds.requireAuthorizationFilter", &value)?,
                None => true,
            },
        };
        let metrics = match lookup("HPDS_METRICS") {
            Some(value) => parse_switch("HPDS_METRICS", &value)?,
            None => false,
        };
        let search_gzip = match lookup("HPDS_SEARCH_GZIP") {
            Some(value) => parse_switch("HPDS_SEARCH_GZIP", &value)?,
            None => false,
        };
        let allow_plain = match lookup("HPDS_ALLOW_PLAIN") {
            Some(value) => parse_bool("HPDS_ALLOW_PLAIN", &value)?,
            None => false,
        };
        let genomic = genomic_config(&lookup)?;
        Ok(Config {
            data_dir: PathBuf::from(
                lookup("HPDS_DATA_DIRECTORY").unwrap_or_else(|| DEFAULT_DATA_DIRECTORY.to_owned()),
            ),
            port: parse_or(&lookup, "SERVER_PORT", DEFAULT_PORT)?,
            partitions: PartitionOptions {
                store: StoreOptions {
                    cache_bytes,
                    allow_plain,
                },
                require_consents,
                prefix_read_bytes: positive_u64(
                    &lookup,
                    "HPDS_PREFIX_READ_BYTES",
                    DEFAULT_PREFIX_READ_BYTES,
                )?,
                prefix_read_budget_bytes: positive_u64(
                    &lookup,
                    "HPDS_PREFIX_READ_BUDGET_BYTES",
                    DEFAULT_PREFIX_READ_BUDGET_BYTES,
                )?,
                ..PartitionOptions::default()
            },
            results_dir: PathBuf::from(
                lookup("HPDS_RESULTS_DIRECTORY")
                    .unwrap_or_else(|| DEFAULT_RESULTS_DIRECTORY.to_owned()),
            ),
            result_limit: positive(&lookup, "HPDS_RESULT_LIMIT", 1000)?,
            result_max_age,
            result_max_bytes: positive_u64(
                &lookup,
                "HPDS_RESULT_MAX_BYTES",
                DEFAULT_RESULT_MAX_BYTES,
            )?,
            id_batch_size: parse_or(&lookup, "ID_BATCH_SIZE", 0)?,
            export_pin_bytes: positive_u64(
                &lookup,
                "HPDS_EXPORT_PIN_BYTES",
                DEFAULT_EXPORT_PIN_BYTES,
            )?,
            result_max_file_bytes: positive_u64(
                &lookup,
                "HPDS_RESULT_MAX_FILE_BYTES",
                DEFAULT_RESULT_MAX_FILE_BYTES,
            )?,
            result_memory_bytes: parse_or(
                &lookup,
                "HPDS_RESULT_MEMORY_BYTES",
                DEFAULT_RESULT_MEMORY_BYTES,
            )?,
            query_limits: QueryLimits {
                max_select_paths: positive(
                    &lookup,
                    "HPDS_MAX_SELECT_PATHS",
                    DEFAULT_MAX_SELECT_PATHS,
                )?,
                max_clause_depth: positive(
                    &lookup,
                    "HPDS_MAX_CLAUSE_DEPTH",
                    DEFAULT_MAX_CLAUSE_DEPTH,
                )?,
                max_clause_nodes: positive(
                    &lookup,
                    "HPDS_MAX_CLAUSE_NODES",
                    DEFAULT_MAX_CLAUSE_NODES,
                )?,
                max_query_concepts: positive(
                    &lookup,
                    "HPDS_MAX_QUERY_CONCEPTS",
                    DEFAULT_MAX_QUERY_CONCEPTS,
                )?,
                max_cross_count_entries: positive_u64(
                    &lookup,
                    "HPDS_MAX_CROSS_COUNT_ENTRIES",
                    DEFAULT_MAX_CROSS_COUNT_ENTRIES,
                )?,
            },
            sync_timeout: Duration::from_secs(positive_u64(
                &lookup,
                "HPDS_SYNC_TIMEOUT_SECONDS",
                DEFAULT_SYNC_TIMEOUT_SECONDS,
            )?),
            log_directory: lookup("HPDS_LOG_DIRECTORY")
                .filter(|dir| !dir.trim().is_empty())
                .map(PathBuf::from),
            small_job_limit: parse_or(&lookup, "SMALL_JOB_LIMIT", 100)?,
            small_task_threads: positive(&lookup, "SMALL_TASK_THREADS", 1)?,
            large_task_threads: positive(&lookup, "LARGE_TASK_THREADS", 1)?,
            export_concurrency: positive(&lookup, "HPDS_EXPORT_CONCURRENCY", 2)?,
            engine_concurrency,
            blocking_threads,
            max_connections: positive(&lookup, "HPDS_MAX_CONNECTIONS", 512)?,
            result_cache_bytes,
            metrics,
            search_gzip,
            excerpt_concurrency,
            excerpt_stall: Duration::from_secs(positive_u64(
                &lookup,
                "HPDS_EXCERPT_STALL_SECONDS",
                DEFAULT_EXCERPT_STALL_SECONDS,
            )?),
            excerpt_stream: Duration::from_secs(positive_u64(
                &lookup,
                "HPDS_EXCERPT_STREAM_SECONDS",
                DEFAULT_EXCERPT_STREAM_SECONDS,
            )?),
            excerpt_queue: Duration::from_secs(parse_or(
                &lookup,
                "HPDS_EXCERPT_QUEUE_SECONDS",
                DEFAULT_EXCERPT_QUEUE_SECONDS,
            )?),
            write_stall: Duration::from_secs(positive_u64(
                &lookup,
                "HPDS_WRITE_STALL_SECONDS",
                DEFAULT_WRITE_STALL_SECONDS,
            )?),
            genomic,
            warnings,
        })
    }
}

/// The value of the first of `names` that is set.
fn first_set(lookup: &impl Fn(&str) -> Option<String>, names: &[&str]) -> Option<(String, String)> {
    names
        .iter()
        .find_map(|name| lookup(name).map(|value| ((*name).to_owned(), value)))
}

/// Reads the genomic settings.
///
/// # Errors
/// A message naming the setting whose value cannot be parsed, an unknown layout, or the
/// `remote` layout, which this HPDS does not serve.
fn genomic_config(lookup: &impl Fn(&str) -> Option<String>) -> Result<GenomicConfig, String> {
    let processor = match first_set(
        lookup,
        &["hpds.genomicProcessor.impl", "HPDS_GENOMICPROCESSOR_IMPL"],
    ) {
        None => None,
        Some((_, value)) if value.trim().is_empty() => None,
        Some((name, value)) => Some(parse_processor(&name, value.trim())?),
    };
    let switch = |name: &str| match lookup(name) {
        Some(value) => parse_switch(name, &value),
        None => Ok(false),
    };
    let id_cube_name = lookup("ID_CUBE_NAME").filter(|value| value != "NONE");
    let info_column_order = first_set(
        lookup,
        &["variant.info_column_order", "VARIANT_INFO_COLUMN_ORDER"],
    )
    .map(|(_, value)| value)
    .unwrap_or_default();
    Ok(GenomicConfig {
        data_dir: PathBuf::from(
            lookup("HPDS_GENOMIC_DATA_DIRECTORY")
                .unwrap_or_else(|| DEFAULT_GENOMIC_DATA_DIRECTORY.to_owned()),
        ),
        processor,
        options: GenomicOptions {
            cache_bytes: positive_u64(
                lookup,
                "HPDS_GENOMIC_CACHE_BYTES",
                DEFAULT_GENOMIC_CACHE_BYTES,
            )?,
            read_budget_bytes: positive_u64(
                lookup,
                "HPDS_GENOMIC_READ_BUDGET_BYTES",
                DEFAULT_GENOMIC_READ_BUDGET_BYTES,
            )?,
            threads: None,
        },
        variant: VariantResultSettings {
            vcf_excerpt_enabled: switch("VCF_EXCERPT_ENABLED")?,
            aggregate_vcf_excerpt_enabled: switch("AGGREGATE_VCF_EXCERPT_ENABLED")?,
            id_cube_name,
            info_column_order,
            max_excerpt_bytes: positive_u64(
                lookup,
                "HPDS_MAX_EXCERPT_BYTES",
                DEFAULT_MAX_EXCERPT_BYTES,
            )?,
        },
    })
}

/// The layout `value` of setting `name` names.
///
/// # Errors
/// A message for `remote`, which this HPDS does not serve, and for any other unknown value.
/// Values are compared ignoring case, as Spring's `havingValue` compares them.
fn parse_processor(name: &str, value: &str) -> Result<GenoMode, String> {
    let modes = [
        GenoMode::Local,
        GenoMode::LocalDistributed,
        GenoMode::LocalPatientDistributed,
        GenoMode::LocalPatientOnlyDistributed,
    ];
    if let Some(mode) = modes
        .into_iter()
        .find(|m| m.java_name().eq_ignore_ascii_case(value))
    {
        return Ok(mode);
    }
    let known = modes.map(GenoMode::java_name).join(", ");
    if value.eq_ignore_ascii_case("remote") {
        return Err(format!(
            "{name}=remote is not supported: this HPDS reads its genomic store in process; set one of {known}"
        ));
    }
    Err(format!(
        "{name} is not valid: {value}; set one of {known}, or leave it unset"
    ))
}

/// Reads the listen port from `SERVER_PORT`, falling back to [`DEFAULT_PORT`].
///
/// # Errors
/// A message when the variable is set but is not a valid port.
pub fn port_from_env() -> Result<u16, String> {
    parse_or(
        &|name: &str| std::env::var(name).ok(),
        "SERVER_PORT",
        DEFAULT_PORT,
    )
}

fn cpu_count() -> usize {
    std::thread::available_parallelism().map_or(1, usize::from)
}

fn parse_or<T: std::str::FromStr>(
    lookup: &impl Fn(&str) -> Option<String>,
    name: &str,
    default: T,
) -> Result<T, String> {
    match lookup(name) {
        Some(value) => value
            .trim()
            .parse()
            .map_err(|_| format!("{name} is not valid: {value}")),
        None => Ok(default),
    }
}

fn positive(
    lookup: &impl Fn(&str) -> Option<String>,
    name: &str,
    default: usize,
) -> Result<usize, String> {
    match parse_or(lookup, name, default)? {
        0 => Err(format!("{name} must be at least 1")),
        value => Ok(value),
    }
}

fn positive_u64(
    lookup: &impl Fn(&str) -> Option<String>,
    name: &str,
    default: u64,
) -> Result<u64, String> {
    match parse_or(lookup, name, default)? {
        0 => Err(format!("{name} must be at least 1")),
        value => Ok(value),
    }
}

/// Reads `value` of setting `name` with the words Spring's `StringToBooleanConverter` reads:
/// `true`, `on`, `yes`, `1` and `false`, `off`, `no`, `0`, ignoring case and surrounding
/// spaces.
///
/// # Errors
/// A message naming the setting and the words it accepts for any other value, the empty
/// value included, which Spring cannot bind to a boolean either.
fn parse_switch(name: &str, value: &str) -> Result<bool, String> {
    match value.trim().to_ascii_lowercase().as_str() {
        "true" | "on" | "yes" | "1" => Ok(true),
        "false" | "off" | "no" | "0" => Ok(false),
        _ => Err(format!(
            "{name} is not a boolean: {value:?}; set true, on, yes or 1, or false, off, no or 0"
        )),
    }
}

fn parse_bool(name: &str, value: &str) -> Result<bool, String> {
    match value.trim().to_ascii_lowercase().as_str() {
        "true" | "1" | "yes" => Ok(true),
        "false" | "0" | "no" => Ok(false),
        _ => Err(format!("{name} is not a boolean: {value}")),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::collections::HashMap;

    fn config(vars: &[(&str, &str)]) -> Result<Config, String> {
        let map: HashMap<String, String> = vars
            .iter()
            .map(|(k, v)| ((*k).to_owned(), (*v).to_owned()))
            .collect();
        Config::from_lookup(|name| map.get(name).cloned())
    }

    #[test]
    fn defaults_match_the_java_service() {
        let c = config(&[]).unwrap();
        assert_eq!(c.data_dir, PathBuf::from("/opt/local/hpds/"));
        assert_eq!(c.port, 8080);
        assert!(c.partitions.require_consents);
        assert!(!c.partitions.store.allow_plain);
        assert_eq!(c.partitions.store.cache_bytes, DEFAULT_CACHE_BYTES);
        assert_eq!(
            (
                c.small_job_limit,
                c.small_task_threads,
                c.large_task_threads
            ),
            (100, 1, 1)
        );
        assert!(c.warnings.is_empty());
        assert_eq!(c.result_max_age, None);
        assert_eq!(c.result_max_bytes, 10 << 30);
        assert_eq!(c.id_batch_size, 0);
        assert_eq!(c.export_pin_bytes, 2 << 30);
        assert_eq!(c.partitions.prefix_read_bytes, 512 << 20);
        assert_eq!(c.partitions.prefix_read_budget_bytes, 1 << 30);
        assert_eq!(c.sync_timeout, Duration::from_secs(300));
        assert_eq!(c.result_limit, 1000);
        assert!(c.engine_concurrency < c.blocking_threads);
        assert_eq!(c.max_connections, 512);
        assert_eq!(c.result_cache_bytes, 0);
        assert_eq!(c.result_max_file_bytes, 4 << 30);
        assert_eq!(c.result_memory_bytes, 64 << 20);
        assert!(!c.metrics);
        assert!(!c.search_gzip);
        assert_eq!(c.query_limits, QueryLimits::default());
        assert_eq!(c.query_limits.max_select_paths, 5_000);
        assert_eq!(c.query_limits.max_clause_depth, 64);
        assert_eq!(c.query_limits.max_clause_nodes, 10_000);
        assert_eq!(c.query_limits.max_query_concepts, 20_000);
        assert!(config(&[("HPDS_MAX_CONNECTIONS", "0")]).is_err());
    }

    #[test]
    fn excerpt_bounds_default_and_stay_below_the_engine_concurrency() {
        let c = config(&[("HPDS_ENGINE_CONCURRENCY", "8")]).unwrap();
        assert_eq!(c.excerpt_concurrency, 4);
        assert_eq!(c.excerpt_stall, Duration::from_secs(30));
        assert_eq!(c.excerpt_stream, Duration::from_secs(600));
        let c = config(&[
            ("HPDS_ENGINE_CONCURRENCY", "4"),
            ("HPDS_EXCERPT_CONCURRENCY", "3"),
            ("HPDS_EXCERPT_STALL_SECONDS", "5"),
            ("HPDS_EXCERPT_STREAM_SECONDS", "60"),
        ])
        .unwrap();
        assert_eq!(
            (c.excerpt_concurrency, c.excerpt_stall, c.excerpt_stream),
            (3, Duration::from_secs(5), Duration::from_secs(60))
        );
        assert!(
            config(&[
                ("HPDS_ENGINE_CONCURRENCY", "4"),
                ("HPDS_EXCERPT_CONCURRENCY", "4")
            ])
            .is_err()
        );
        assert!(config(&[("HPDS_EXCERPT_STALL_SECONDS", "0")]).is_err());
        let single = config(&[("HPDS_ENGINE_CONCURRENCY", "1")]).unwrap();
        assert_eq!(single.excerpt_concurrency, 1);
        assert_eq!(single.warnings.len(), 1);
        let over = config(&[
            ("HPDS_ENGINE_CONCURRENCY", "1"),
            ("HPDS_EXCERPT_CONCURRENCY", "2"),
        ])
        .unwrap_err();
        assert!(over.contains("must be 1"), "{over}");
        assert_eq!(c.excerpt_queue, Duration::from_secs(5));
        assert_eq!(c.write_stall, Duration::from_secs(60));
        let c = config(&[
            ("HPDS_EXCERPT_QUEUE_SECONDS", "0"),
            ("HPDS_WRITE_STALL_SECONDS", "7"),
        ])
        .unwrap();
        assert_eq!(
            (c.excerpt_queue, c.write_stall),
            (Duration::ZERO, Duration::from_secs(7))
        );
        assert!(config(&[("HPDS_WRITE_STALL_SECONDS", "0")]).is_err());
    }

    #[test]
    fn genomic_settings_default_to_javas_and_are_read() {
        let c = config(&[]).unwrap().genomic;
        assert_eq!(c.data_dir, PathBuf::from("/opt/local/hpds/all/"));
        assert_eq!(c.processor, None);
        assert_eq!(c.options, GenomicOptions::default());
        assert_eq!(c.variant, VariantResultSettings::default());
        let c = config(&[
            ("HPDS_GENOMIC_DATA_DIRECTORY", "/data/genomic/"),
            ("hpds.genomicProcessor.impl", "localPatientDistributed"),
            ("HPDS_GENOMIC_CACHE_BYTES", "1048576"),
            ("HPDS_GENOMIC_READ_BUDGET_BYTES", "2097152"),
            ("VCF_EXCERPT_ENABLED", "true"),
            ("AGGREGATE_VCF_EXCERPT_ENABLED", "on"),
            ("ID_CUBE_NAME", "\\demo\\id\\"),
            ("variant.info_column_order", "AF,Gene_with_variant"),
            ("HPDS_MAX_EXCERPT_BYTES", "4096"),
        ])
        .unwrap()
        .genomic;
        assert_eq!(c.data_dir, PathBuf::from("/data/genomic/"));
        assert_eq!(c.processor, Some(GenoMode::LocalPatientDistributed));
        assert_eq!(c.options.cache_bytes, 1 << 20);
        assert_eq!(c.options.read_budget_bytes, 2 << 20);
        assert!(c.variant.vcf_excerpt_enabled && c.variant.aggregate_vcf_excerpt_enabled);
        assert_eq!(c.variant.id_cube_name.as_deref(), Some("\\demo\\id\\"));
        assert_eq!(c.variant.info_column_order, "AF,Gene_with_variant");
        assert_eq!(c.variant.max_excerpt_bytes, 4096);
        let c = config(&[
            ("HPDS_GENOMICPROCESSOR_IMPL", "local"),
            ("ID_CUBE_NAME", "NONE"),
            ("VARIANT_INFO_COLUMN_ORDER", "AF"),
        ])
        .unwrap()
        .genomic;
        assert_eq!(c.processor, Some(GenoMode::Local));
        assert_eq!(c.variant.id_cube_name, None);
        assert_eq!(c.variant.info_column_order, "AF");
        assert_eq!(
            config(&[("hpds.genomicProcessor.impl", " ")])
                .unwrap()
                .genomic
                .processor,
            None
        );
        let remote = config(&[("hpds.genomicProcessor.impl", "remote")]).unwrap_err();
        assert!(remote.contains("remote is not supported"), "{remote}");
        assert_eq!(
            config(&[("hpds.genomicProcessor.impl", "LOCALPATIENTDISTRIBUTED")])
                .unwrap()
                .genomic
                .processor,
            Some(GenoMode::LocalPatientDistributed)
        );
        assert!(
            config(&[("hpds.genomicProcessor.impl", "Remote")])
                .unwrap_err()
                .contains("not supported")
        );
        assert!(config(&[("hpds.genomicProcessor.impl", "localized")]).is_err());
        assert!(config(&[("VCF_EXCERPT_ENABLED", "maybe")]).is_err());
    }

    #[test]
    fn excerpt_gates_read_springs_boolean_words() {
        let gates = |value: &str| {
            config(&[
                ("VCF_EXCERPT_ENABLED", value),
                ("AGGREGATE_VCF_EXCERPT_ENABLED", value),
            ])
            .map(|c| {
                (
                    c.genomic.variant.vcf_excerpt_enabled,
                    c.genomic.variant.aggregate_vcf_excerpt_enabled,
                )
            })
        };
        for word in ["true", "TRUE", "On", "yes", "YES", "1", " true "] {
            assert_eq!(gates(word), Ok((true, true)), "{word}");
        }
        for word in ["false", "False", "OFF", "no", "No", "0"] {
            assert_eq!(gates(word), Ok((false, false)), "{word}");
        }
        for word in ["", "maybe", "y", "n", "enabled", "2", "t"] {
            for name in ["VCF_EXCERPT_ENABLED", "AGGREGATE_VCF_EXCERPT_ENABLED"] {
                let error = config(&[(name, word)]).unwrap_err();
                assert!(
                    error.contains(name) && error.contains("true, on, yes or 1"),
                    "{error}"
                );
            }
        }
        assert!(config(&[("HPDS_GENOMIC_CACHE_BYTES", "0")]).is_err());
        assert!(config(&[("HPDS_MAX_EXCERPT_BYTES", "0")]).is_err());
    }

    #[test]
    fn prefix_read_bytes_is_read_and_positive() {
        assert_eq!(
            config(&[("HPDS_PREFIX_READ_BYTES", "1048576")])
                .unwrap()
                .partitions
                .prefix_read_bytes,
            1 << 20
        );
        assert!(config(&[("HPDS_PREFIX_READ_BYTES", "0")]).is_err());
        assert!(config(&[("HPDS_PREFIX_READ_BYTES", "x")]).is_err());
        assert_eq!(
            config(&[("HPDS_PREFIX_READ_BUDGET_BYTES", "2097152")])
                .unwrap()
                .partitions
                .prefix_read_budget_bytes,
            2 << 20
        );
        assert!(config(&[("HPDS_PREFIX_READ_BUDGET_BYTES", "0")]).is_err());
    }

    #[test]
    fn result_memory_bytes_is_read_and_may_be_zero() {
        assert_eq!(
            config(&[("HPDS_RESULT_MEMORY_BYTES", "0")])
                .unwrap()
                .result_memory_bytes,
            0
        );
        assert_eq!(
            config(&[("HPDS_RESULT_MEMORY_BYTES", "1048576")])
                .unwrap()
                .result_memory_bytes,
            1 << 20
        );
        assert!(config(&[("HPDS_RESULT_MEMORY_BYTES", "-1")]).is_err());
    }

    #[test]
    fn metrics_switch_is_read() {
        for (value, on) in [
            ("on", true),
            ("ON", true),
            ("true", true),
            ("off", false),
            ("0", false),
        ] {
            assert_eq!(
                config(&[("HPDS_METRICS", value)]).unwrap().metrics,
                on,
                "{value}"
            );
        }
        let error = config(&[("HPDS_METRICS", "maybe")]).unwrap_err();
        assert!(error.contains("HPDS_METRICS"), "{error}");
        assert!(!config(&[("HPDS_SEARCH_GZIP", "off")]).unwrap().search_gzip);
        assert!(config(&[("HPDS_SEARCH_GZIP", "on")]).unwrap().search_gzip);
        assert!(config(&[("HPDS_SEARCH_GZIP", "sometimes")]).is_err());
    }

    #[test]
    fn result_cache_bytes_is_read() {
        let c = config(&[("HPDS_RESULT_CACHE_BYTES", "268435456")]).unwrap();
        assert_eq!(c.result_cache_bytes, 256 << 20);
        assert!(config(&[("HPDS_RESULT_CACHE_BYTES", "lots")]).is_err());
        assert!(c.warnings.is_empty());
    }

    #[test]
    fn a_result_cache_bound_too_small_to_store_anything_is_warned_about() {
        let min = hpds_query::MIN_RESULT_CACHE_BYTES;
        for (bytes, warned) in [(0, false), (1, true), (min - 1, true), (min, false)] {
            let c = config(&[("HPDS_RESULT_CACHE_BYTES", &bytes.to_string())]).unwrap();
            assert_eq!(c.result_cache_bytes, bytes);
            assert_eq!(
                c.warnings.len(),
                usize::from(warned),
                "{bytes}: {:?}",
                c.warnings
            );
        }
    }

    #[test]
    fn result_age_zero_means_unlimited() {
        assert_eq!(
            config(&[("HPDS_RESULT_MAX_AGE_SECONDS", "0")])
                .unwrap()
                .result_max_age,
            None
        );
        assert_eq!(
            config(&[("HPDS_RESULT_MAX_AGE_SECONDS", "60")])
                .unwrap()
                .result_max_age,
            Some(Duration::from_secs(60))
        );
    }

    #[test]
    fn cache_bytes_old_name_is_accepted_with_a_warning() {
        let old = config(&[("CACHE_BYTES", "2048")]).unwrap();
        assert_eq!(old.partitions.store.cache_bytes, 2048);
        assert_eq!(old.warnings.len(), 1);
        let both = config(&[("CACHE_BYTES", "2048"), ("HPDS_CACHE_BYTES", "4096")]).unwrap();
        assert_eq!(both.partitions.store.cache_bytes, 4096);
        let new = config(&[("HPDS_CACHE_BYTES", "4096")]).unwrap();
        assert_eq!(
            (new.partitions.store.cache_bytes, new.warnings.len()),
            (4096, 0)
        );
    }

    #[test]
    fn engine_concurrency_must_leave_blocking_headroom() {
        let error = config(&[
            ("HPDS_ENGINE_CONCURRENCY", "8"),
            ("HPDS_BLOCKING_THREADS", "8"),
        ])
        .unwrap_err();
        assert!(error.contains("HPDS_ENGINE_CONCURRENCY"), "{error}");
        assert!(config(&[("HPDS_ENGINE_CONCURRENCY", "128")]).is_ok());
        assert!(
            config(&[
                ("HPDS_ENGINE_CONCURRENCY", "8"),
                ("HPDS_BLOCKING_THREADS", "9")
            ])
            .is_ok()
        );
    }

    #[test]
    fn authorization_flag_accepts_both_spellings() {
        assert!(
            !config(&[("hpds.requireAuthorizationFilter", "false")])
                .unwrap()
                .partitions
                .require_consents
        );
        assert!(
            config(&[
                ("HPDS_REQUIRE_AUTHORIZATION_FILTER", "true"),
                ("hpds.requireAuthorizationFilter", "false")
            ])
            .unwrap()
            .partitions
            .require_consents
        );
    }

    #[test]
    fn settings_of_the_previous_layout_are_ignored_with_a_warning() {
        let c = config(&[
            ("HPDS_COMBINED_STORE", "off"),
            ("HPDS_PER_PARTITION_COUNTS", "x"),
        ])
        .unwrap();
        assert_eq!(c.partitions, config(&[]).unwrap().partitions);
        assert_eq!(c.warnings.len(), 2);
        assert!(c.warnings[0].contains("HPDS_COMBINED_STORE"));
        assert!(c.warnings[1].contains("HPDS_PER_PARTITION_COUNTS"));
    }

    #[test]
    fn cache_size_is_ignored_with_a_warning() {
        let c = config(&[("CACHE_SIZE", "1500"), ("HPDS_CACHE_BYTES", "1024")]).unwrap();
        assert_eq!(c.partitions.store.cache_bytes, 1024);
        assert_eq!(c.warnings.len(), 1);
    }

    #[test]
    fn invalid_values_are_errors() {
        assert!(config(&[("SERVER_PORT", "http")]).is_err());
        assert!(config(&[("SMALL_TASK_THREADS", "0")]).is_err());
        assert!(config(&[("HPDS_ALLOW_PLAIN", "maybe")]).is_err());
        assert!(config(&[("HPDS_SYNC_TIMEOUT_SECONDS", "0")]).is_err());
        assert!(config(&[("HPDS_RESULT_MAX_BYTES", "0")]).is_err());
        for name in [
            "HPDS_RESULT_MAX_FILE_BYTES",
            "HPDS_MAX_SELECT_PATHS",
            "HPDS_MAX_CLAUSE_DEPTH",
            "HPDS_MAX_CLAUSE_NODES",
            "HPDS_MAX_QUERY_CONCEPTS",
            "HPDS_MAX_CROSS_COUNT_ENTRIES",
        ] {
            assert!(config(&[(name, "0")]).is_err(), "{name}");
            assert!(config(&[(name, "x")]).is_err(), "{name}");
            assert!(config(&[(name, "7")]).is_ok(), "{name}");
        }
        let c = config(&[
            ("HPDS_MAX_SELECT_PATHS", "7"),
            ("HPDS_RESULT_MAX_FILE_BYTES", "9"),
        ])
        .unwrap();
        assert_eq!(
            (c.query_limits.max_select_paths, c.result_max_file_bytes),
            (7, 9)
        );
    }
}
