//! Service settings read from the environment.

use std::path::PathBuf;
use std::time::Duration;

use hpds_query::{DEFAULT_CACHE_BYTES, DEFAULT_DATA_DIRECTORY, StoreOptions};

/// Port used when `SERVER_PORT` is not set.
pub const DEFAULT_PORT: u16 = 8080;

/// Directory for export result files when `HPDS_RESULTS_DIRECTORY` is not set.
pub const DEFAULT_RESULTS_DIRECTORY: &str = "/tmp/hpds-rs-results";

/// Largest request body accepted, in bytes.
pub const MAX_BODY_BYTES: usize = 2 << 20;

/// Every setting the service reads at startup.
///
/// | Variable | Default | Meaning |
/// |---|---|---|
/// | `HPDS_DATA_DIRECTORY` | `/opt/local/hpds/` | Store directory |
/// | `SERVER_PORT` | 8080 | Listen port |
/// | `CACHE_BYTES` | 4 GiB | Byte bound of the decoded block cache |
/// | `CACHE_SIZE` | ignored | Java's cache size in cubes; logged and ignored |
/// | `HPDS_ALLOW_PLAIN` | false | Open a store without a key file (benchmarks only) |
/// | `HPDS_REQUIRE_AUTHORIZATION_FILTER` or `hpds.requireAuthorizationFilter` | true | Refuse async queries without an authorization filter |
/// | `HPDS_RESULTS_DIRECTORY` | `/tmp/hpds-rs-results` | Where export results are written |
/// | `HPDS_RESULT_LIMIT` | 1000 | Most async results held at once |
/// | `HPDS_RESULT_MAX_AGE_SECONDS` | 3600 | How long a finished result is kept |
/// | `SMALL_JOB_LIMIT` | 100 | An export with more `select` paths than this runs on the large pool |
/// | `SMALL_TASK_THREADS` | 1 | Exports run at once on the small pool |
/// | `LARGE_TASK_THREADS` | 1 | Exports run at once on the large pool |
/// | `HPDS_EXPORT_CONCURRENCY` | 2 | Exports run at once across both pools; each pins its selected blocks |
/// | `HPDS_ENGINE_CONCURRENCY` | CPU count | Engine computations (sync queries and exports) run at once |
/// | `HPDS_BLOCKING_THREADS` | 64 | Cap on tokio's blocking threads, which run every engine call |
#[derive(Debug, Clone, PartialEq)]
pub struct Config {
    /// The store directory.
    pub data_dir: PathBuf,
    /// The listen port.
    pub port: u16,
    /// How the store is opened.
    pub store: StoreOptions,
    /// Whether `POST /query` refuses a query with no authorization filter.
    pub require_authorization_filter: bool,
    /// Directory for export result files.
    pub results_dir: PathBuf,
    /// Most async results held at once.
    pub result_limit: usize,
    /// How long a finished result is kept.
    pub result_max_age: Duration,
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
    /// A message naming the first variable whose value cannot be parsed.
    pub fn from_lookup(lookup: impl Fn(&str) -> Option<String>) -> Result<Self, String> {
        let mut warnings = Vec::new();
        if lookup("CACHE_SIZE").is_some() {
            warnings.push(
                "CACHE_SIZE counts cubes in the Java service and is ignored; set CACHE_BYTES"
                    .to_owned(),
            );
        }
        let require_authorization_filter = match lookup("HPDS_REQUIRE_AUTHORIZATION_FILTER") {
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
        Ok(Config {
            data_dir: PathBuf::from(
                lookup("HPDS_DATA_DIRECTORY").unwrap_or_else(|| DEFAULT_DATA_DIRECTORY.to_owned()),
            ),
            port: parse_or(&lookup, "SERVER_PORT", DEFAULT_PORT)?,
            store: StoreOptions {
                cache_bytes: parse_or(&lookup, "CACHE_BYTES", DEFAULT_CACHE_BYTES)?,
                allow_plain,
            },
            require_authorization_filter,
            results_dir: PathBuf::from(
                lookup("HPDS_RESULTS_DIRECTORY")
                    .unwrap_or_else(|| DEFAULT_RESULTS_DIRECTORY.to_owned()),
            ),
            result_limit: positive(&lookup, "HPDS_RESULT_LIMIT", 1000)?,
            result_max_age: Duration::from_secs(parse_or(
                &lookup,
                "HPDS_RESULT_MAX_AGE_SECONDS",
                3600,
            )?),
            small_job_limit: parse_or(&lookup, "SMALL_JOB_LIMIT", 100)?,
            small_task_threads: positive(&lookup, "SMALL_TASK_THREADS", 1)?,
            large_task_threads: positive(&lookup, "LARGE_TASK_THREADS", 1)?,
            export_concurrency: positive(&lookup, "HPDS_EXPORT_CONCURRENCY", 2)?,
            engine_concurrency: positive(&lookup, "HPDS_ENGINE_CONCURRENCY", cpu_count())?,
            blocking_threads: positive(&lookup, "HPDS_BLOCKING_THREADS", 64)?,
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
        assert!(c.require_authorization_filter);
        assert!(!c.store.allow_plain);
        assert_eq!(c.store.cache_bytes, DEFAULT_CACHE_BYTES);
        assert_eq!(
            (
                c.small_job_limit,
                c.small_task_threads,
                c.large_task_threads
            ),
            (100, 1, 1)
        );
        assert!(c.warnings.is_empty());
    }

    #[test]
    fn authorization_flag_accepts_both_spellings() {
        assert!(
            !config(&[("hpds.requireAuthorizationFilter", "false")])
                .unwrap()
                .require_authorization_filter
        );
        assert!(
            config(&[
                ("HPDS_REQUIRE_AUTHORIZATION_FILTER", "true"),
                ("hpds.requireAuthorizationFilter", "false")
            ])
            .unwrap()
            .require_authorization_filter
        );
    }

    #[test]
    fn cache_size_is_ignored_with_a_warning() {
        let c = config(&[("CACHE_SIZE", "1500"), ("CACHE_BYTES", "1024")]).unwrap();
        assert_eq!(c.store.cache_bytes, 1024);
        assert_eq!(c.warnings.len(), 1);
    }

    #[test]
    fn invalid_values_are_errors() {
        assert!(config(&[("SERVER_PORT", "http")]).is_err());
        assert!(config(&[("SMALL_TASK_THREADS", "0")]).is_err());
        assert!(config(&[("HPDS_ALLOW_PLAIN", "maybe")]).is_err());
    }
}
