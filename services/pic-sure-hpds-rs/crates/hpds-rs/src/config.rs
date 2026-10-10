//! Service settings read from the environment.

use std::path::PathBuf;
use std::time::Duration;

use hpds_query::{CombinedMode, PerPartitionCounts};
use hpds_query::{
    DEFAULT_CACHE_BYTES, DEFAULT_DATA_DIRECTORY, DEFAULT_MAX_CLAUSE_DEPTH,
    DEFAULT_MAX_CLAUSE_NODES, DEFAULT_MAX_CROSS_COUNT_ENTRIES, DEFAULT_MAX_QUERY_CONCEPTS,
    DEFAULT_MAX_SELECT_PATHS, PartitionOptions, QueryLimits, StoreOptions,
};

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

/// Default seconds a sync query may run before the request is answered 503.
pub const DEFAULT_SYNC_TIMEOUT_SECONDS: u64 = 300;

const DEFAULT_BLOCKING_THREADS: usize = 64;
const BLOCKING_THREAD_HEADROOM: usize = 16;

/// Every setting the service reads at startup.
///
/// | Variable | Default | Meaning |
/// |---|---|---|
/// | `HPDS_DATA_DIRECTORY` | `/opt/local/hpds/` | Data directory: one store per consent subdirectory, or one unpartitioned store |
/// | `SERVER_PORT` | 8080 | Listen port |
/// | `HPDS_CACHE_BYTES` (or the old name `CACHE_BYTES`, with a warning) | 4 GiB | Byte bound of the decoded block cache |
/// | `HPDS_RESULT_CACHE_BYTES` | 0 (off) | Byte bound of the cache of COUNT, CROSS_COUNT, CATEGORICAL_CROSS_COUNT, CONTINUOUS_CROSS_COUNT and OBSERVATION_CROSS_COUNT answers |
/// | `CACHE_SIZE` | ignored | Java's cache size in cubes; logged and ignored |
/// | `HPDS_ALLOW_PLAIN` | false | Open a store without a key file (benchmarks only) |
/// | `HPDS_REQUIRE_AUTHORIZATION_FILTER` or `hpds.requireAuthorizationFilter` | true | Refuse every sync and async query without a `userConsents` value, and refuse to open an unpartitioned data directory |
/// | `HPDS_COMBINED_STORE` | `auto` | `auto` answers queries that select every partition from the combined store when the data directory has a valid one; `off` ignores it |
/// | `HPDS_PER_PARTITION_COUNTS` | `auto` | `auto` counts cross count patients in each selected partition and sums the counts when the partitions are patient-disjoint and every selected partition carries the `patients-indexed` record in `.generation`; `off` always unions the partitions' patient sets first |
/// | `HPDS_RESULTS_DIRECTORY` | `/tmp/hpds-rs-results` | Where export results are written |
/// | `HPDS_RESULT_LIMIT` | 1000 | Most async results held at once |
/// | `HPDS_RESULT_MAX_AGE_SECONDS` | unlimited | How long a finished result is kept; 0 or unset keeps it until evicted |
/// | `HPDS_RESULT_MAX_BYTES` | 10 GiB | Byte cap on finished export files; the oldest finished result goes first |
/// | `ID_BATCH_SIZE` | 0 | Java's export switch: 0 disables DATAFRAME exports, any larger value enables them |
/// | `HPDS_EXPORT_PIN_BYTES` | 2 GiB | Cap on the decoded blocks one DATAFRAME export holds; a wider export ends in ERROR |
/// | `HPDS_RESULT_MAX_FILE_BYTES` | 4 GiB | Cap on one export file, checked as it is written; a larger export ends in ERROR and its partial file is deleted |
/// | `HPDS_MAX_SELECT_PATHS` | 5,000 | Most distinct `select` paths in a query |
/// | `HPDS_MAX_CLAUSE_DEPTH` | 64 | Deepest nesting of phenotypic subqueries |
/// | `HPDS_MAX_CLAUSE_NODES` | 10,000 | Most clauses and authorization filters in a query |
/// | `HPDS_MAX_QUERY_CONCEPTS` | 20,000 | Most concepts a query touches, `ANY_RECORD_OF` expansions included, counted over the union of the partitions its consents select |
/// | `HPDS_MAX_CROSS_COUNT_ENTRIES` | 5,000,000 | Most entries in a categorical or continuous cross count |
/// | `HPDS_SYNC_TIMEOUT_SECONDS` | 300 | A sync query running longer is answered 503 |
/// | `HPDS_LOG_DIRECTORY` | unset | When set, the log is also written to a daily-rotated file there |
/// | `SMALL_JOB_LIMIT` | 100 | An export with more `select` paths than this runs on the large pool |
/// | `SMALL_TASK_THREADS` | 1 | Exports run at once on the small pool |
/// | `LARGE_TASK_THREADS` | 1 | Exports run at once on the large pool |
/// | `HPDS_EXPORT_CONCURRENCY` | 2 | Exports run at once across both pools; each pins its selected blocks |
/// | `HPDS_ENGINE_CONCURRENCY` | CPU count | Engine computations (sync queries and exports) run at once |
/// | `HPDS_MAX_CONNECTIONS` | 512 | Most connections served at once; a connection past the cap is answered 503 and closed |
/// | `HPDS_BLOCKING_THREADS` | 64, or the engine concurrency plus 16 when larger | Cap on tokio's blocking threads, which run every engine call; must exceed `HPDS_ENGINE_CONCURRENCY` |
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
        let allow_plain = match lookup("HPDS_ALLOW_PLAIN") {
            Some(value) => parse_bool("HPDS_ALLOW_PLAIN", &value)?,
            None => false,
        };
        let combined = match lookup("HPDS_COMBINED_STORE") {
            Some(value) => parse_combined_mode(&value)?,
            None => CombinedMode::Auto,
        };
        let per_partition_counts = match lookup("HPDS_PER_PARTITION_COUNTS") {
            Some(value) => parse_per_partition_counts(&value)?,
            None => PerPartitionCounts::Auto,
        };
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
                combined,
                per_partition_counts,
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
            warnings,
        })
    }
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

fn parse_per_partition_counts(value: &str) -> Result<PerPartitionCounts, String> {
    match value.trim().to_ascii_lowercase().as_str() {
        "auto" => Ok(PerPartitionCounts::Auto),
        "off" => Ok(PerPartitionCounts::Off),
        _ => Err(format!(
            "HPDS_PER_PARTITION_COUNTS is not valid: {value} (use auto or off)"
        )),
    }
}

fn parse_combined_mode(value: &str) -> Result<CombinedMode, String> {
    match value.trim().to_ascii_lowercase().as_str() {
        "auto" => Ok(CombinedMode::Auto),
        "off" => Ok(CombinedMode::Off),
        _ => Err(format!(
            "HPDS_COMBINED_STORE is not valid: {value} (use auto or off)"
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
        assert_eq!(c.sync_timeout, Duration::from_secs(300));
        assert_eq!(c.result_limit, 1000);
        assert!(c.engine_concurrency < c.blocking_threads);
        assert_eq!(c.max_connections, 512);
        assert_eq!(c.result_cache_bytes, 0);
        assert_eq!(c.result_max_file_bytes, 4 << 30);
        assert_eq!(c.query_limits, QueryLimits::default());
        assert_eq!(c.query_limits.max_select_paths, 5_000);
        assert_eq!(c.query_limits.max_clause_depth, 64);
        assert_eq!(c.query_limits.max_clause_nodes, 10_000);
        assert_eq!(c.query_limits.max_query_concepts, 20_000);
        assert!(config(&[("HPDS_MAX_CONNECTIONS", "0")]).is_err());
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
        assert_eq!(config(&[]).unwrap().partitions.combined, CombinedMode::Auto);
        assert_eq!(
            config(&[("HPDS_COMBINED_STORE", " OFF ")])
                .unwrap()
                .partitions
                .combined,
            CombinedMode::Off
        );
        assert_eq!(
            config(&[("HPDS_COMBINED_STORE", "auto")])
                .unwrap()
                .partitions
                .combined,
            CombinedMode::Auto
        );
        assert!(config(&[("HPDS_COMBINED_STORE", "on")]).is_err());
        assert_eq!(
            config(&[]).unwrap().partitions.per_partition_counts,
            PerPartitionCounts::Auto
        );
        assert_eq!(
            config(&[("HPDS_PER_PARTITION_COUNTS", " Off ")])
                .unwrap()
                .partitions
                .per_partition_counts,
            PerPartitionCounts::Off
        );
        assert!(config(&[("HPDS_PER_PARTITION_COUNTS", "yes")]).is_err());
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
