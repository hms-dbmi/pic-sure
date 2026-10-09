# pic-sure-hpds-rs

Rust prototype of the HPDS phenotype store and query service. It is a Cargo workspace outside
the Maven reactor and is not listed in any pom.

## Crates

| Crate | Kind | Purpose |
|---|---|---|
| `hpds-format` | lib | On-disk index and block format, crypto and Java parity helpers |
| `hpds-etl` | bin + lib | Loads CSV and migrates Java javabins into the store (`hpds-etl`) |
| `hpds-query` | lib | Query engine over the store; currently the health-state check only |
| `hpds-rs` | bin + lib | HTTP server (`hpds-rs`) over `hpds-query`, serving the HPDS v3 contract |
| `hpds-gen` | bin | Deterministic synthetic dataset generator |
| `hpds-bench` | bin | Query corpus, compare and load harness |

## Build and test

The toolchain is pinned in `rust-toolchain.toml`. With rustup on the host:

```bash
cargo test --workspace
cargo clippy --workspace --all-targets -- -D warnings
```

Without rustup, `bin/cargo` runs the same commands in the pinned builder image, with named
volumes `hpds-rs-cargo-home` and `hpds-rs-target` caching the registry and build output:

```bash
bin/cargo test --workspace
```

## Images

Build from this directory:

```bash
docker build -f docker/hpds-rs.Dockerfile -t hms-dbmi/pic-sure-hpds-rs:LATEST .
docker build -f docker/hpds-rs-etl.Dockerfile -t hms-dbmi/pic-sure-hpds-rs-etl:LATEST .
```

`hpds-rs` reads these environment variables:

| Variable | Default | Meaning |
|---|---|---|
| `HPDS_DATA_DIRECTORY` | `/opt/local/hpds/` | Store directory. Matches the Java service; the all-in-one stack bind-mounts the hpds-rs host directory here. |
| `SERVER_PORT` | `8080` | Listen port on `0.0.0.0`. |
| `HPDS_CACHE_BYTES` | 4 GiB | Byte bound of the decoded block cache. The old name `CACHE_BYTES` is still read, with a warning. |
| `CACHE_SIZE` | ignored | Java's cube count; logged as ignored. |
| `HPDS_ALLOW_PLAIN` | `false` | Open a store with no key file. Local benchmarking only. |
| `HPDS_REQUIRE_AUTHORIZATION_FILTER` | `true` | Rust-only name for Java's `hpds.requireAuthorizationFilter`, which is also accepted as a variable of that exact name. Refuse `POST /query` without an authorization filter, as Java does. The all-in-one sets it false. |
| `ID_BATCH_SIZE` | `0` | Java's export switch. `0` ends every DATAFRAME export in ERROR with "exports are disabled (ID_BATCH_SIZE=0)"; any larger value enables exports (the batch size itself does not change the algorithm). PATIENTS is unaffected. |
| `HPDS_EXPORT_PIN_BYTES` | 2 GiB | Cap on the decoded blocks one DATAFRAME export holds in memory. A wider export ends in ERROR with "export too wide; select fewer concepts". |
| `HPDS_SYNC_TIMEOUT_SECONDS` | `300` | A sync query that has not answered by then, queueing included, is a 503. Its computation still finishes and then releases its slot. |
| `HPDS_RESULTS_DIRECTORY` | `/tmp/hpds-rs-results` | Export files (files 0600). A missing directory is created with mode 0700. An existing one must be a real directory (not a symlink) owned by the service user with no group or other write bit, and is never chmodded. It must not be, or lie inside, `HPDS_DATA_DIRECTORY`. Leftover export files are removed at startup. |
| `HPDS_RESULT_LIMIT` | `1000` | Most async results held at once. |
| `HPDS_RESULT_MAX_AGE_SECONDS` | unlimited | How long a finished result is kept. `0` or unset keeps it until the count or byte cap evicts it. |
| `HPDS_RESULT_MAX_BYTES` | 10 GiB | Cap on finished export files held at once. Past it the oldest finished result goes first; a lone result larger than the cap is kept. |
| `SMALL_JOB_LIMIT` | `100` | Exports with more `select` paths run on the large pool. |
| `SMALL_TASK_THREADS`, `LARGE_TASK_THREADS` | `1`, `1` | Exports running at once per pool. |
| `HPDS_EXPORT_CONCURRENCY` | `2` | Exports running at once across both pools; each pins its selected blocks in memory. |
| `HPDS_MAX_CONNECTIONS` | `512` | Most connections served at once. A connection accepted at the cap is answered `503` (`text/plain`, `Retry-After: 1`) and closed, so clients and the health probe fail fast instead of waiting in the kernel backlog. A request body must arrive in full within 30 seconds of the request starting (408 otherwise), and a connection idle between requests is closed after 10 seconds. |
| `HPDS_ENGINE_CONCURRENCY` | CPU count | Engine computations (sync queries and exports) running at once. |
| `HPDS_BLOCKING_THREADS` | `64`, or the engine concurrency plus 16 when that is larger | Cap on tokio's blocking threads. Startup fails unless `HPDS_ENGINE_CONCURRENCY` is below it, so file reads keep some blocking threads. |
| `RUST_LOG` | `info` | Log filter, in `tracing` `EnvFilter` syntax. The `hpds_rs::access` target logs one line per request at info with method, path, status and latency. For benchmarks use `RUST_LOG=info,hpds_rs::access=warn` to silence it. |
| `HPDS_LOG_DIRECTORY` | unset | When set, the log is also written to a daily-rotated `hpds-rs.<date>.log` in that directory, so a mounted log directory is useful. Logging goes through a non-blocking writer; under extreme load it drops lines rather than slowing requests. |

The key is read from `<HPDS_DATA_DIRECTORY>/encryption_key`. When the store cannot be opened
the server still starts, health reports DOWN, and query routes answer 503.

### Routes

Mounted under both `/PIC-SURE/v3` and `/PIC-SURE`, because the query service calls search on the
unversioned path. Request bodies are capped at 2 MiB, a body that stalls for 30 seconds is cut
off, and a connection that takes more than 10 seconds to send a request's headers is closed.

| Route | Answer |
|---|---|
| `POST /query/sync` | COUNT (`text/plain`) and the four cross counts (`application/json`), byte for byte as Java, with a `queryMetadata` header; INFO_COLUMN_LISTING is `[]` |
| `POST /query` | `QueryStatus` for a PATIENTS or DATAFRAME export; DATAFRAME_TIMESERIES and DATAFRAME_PFB end in ERROR |
| `POST /query/{id}/status` | `QueryStatus`, with Java's status mapping |
| `POST /query/{id}/result` | The CSV export as `text/plain`; 400 `Status : <state>` before it succeeds |
| `POST /query/{id}/signed-url` | 400, S3 upload is out of scope |
| `POST /query/format`, `POST /write/{dataType}` | 400, not supported |
| `POST /search` | `phenotypes` matched by path substring or category value, `info: {}`. Paths and categories are lowercased once at startup; see the locale note in `search.rs`. |
| `GET /search/values/` | An empty page |
| `GET /actuator/health` | 200 `{"status":"UP"}` when the store is open with at least one concept, else 503 DOWN |

Differences from Java a caller can see:

- Error bodies are `text/plain` messages, client and server errors alike, so the query service
  shows a readable message. Invalid requests are 400, where Java answers 500, an empty 200, or
  a 200 status of ERROR. Unknown result ids are 404 on every route.
- Request bodies written in the v1 query model (`fields`, `crossCountFields`, `requiredFields`,
  `anyRecordOf`, `numericFilters`, `categoryFilters`, `variantInfoFilters`) are refused with 400
  "v1 queries are not supported; use /PIC-SURE/v3". Inside a v3 query any other unknown field
  is also a 400.
- `POST /query` accepts only PATIENTS, DATAFRAME, DATAFRAME_TIMESERIES and DATAFRAME_PFB. Java
  also accepts COUNT, CATEGORICAL_CROSS_COUNT and CONTINUOUS_CROSS_COUNT there, running them
  through its count processor, and COUNT then ends in ERROR. Here every sync type submitted
  asynchronously is a 400 pointing at `/query/sync`.
- A full registry (every held result pending or running) answers `POST /query` with 503. The
  query service maps that to 502.
- The query service reaches this service by two paths. Through the authorization path it passes
  a 4xx from HPDS on to the caller. The `/hpds/open` path maps any non-2xx to 502, so a 400
  from this service shows up there as a 502.
- `/query/sync` does not validate, as Java does not, so an authorization filter on a concept
  the store lacks counts 0. `POST /query` validates as Java does, but refuses with a 400 instead
  of registering an ERROR result.
- Result ids and `queryMetadata` are random UUIDs, not Java's UUIDv5 of the query text.
- A failed query's `resultMetadata` carries an `error` message.
- Results are dropped oldest finished first when `HPDS_RESULT_LIMIT` or `HPDS_RESULT_MAX_BYTES`
  is reached, and after `HPDS_RESULT_MAX_AGE_SECONDS` when that is set; Java keeps them until
  restart.
- Search leaves out `timestampMin` and `timestampMax`. No Java loader sets them, so Java never
  writes them.
- Genomic input (genomic result types, `genomicFilters`, variant-spec paths) is a 400 naming the
  unsupported feature.

On SIGTERM or ctrl-c the server stops accepting, gives open connections 5 seconds to finish,
then gives the runtime 5 more seconds before abandoning running queries. `hpds-rs healthcheck` requests
`http://127.0.0.1:$SERVER_PORT/actuator/health` and exits 0 on a 200 `UP`, 1 otherwise; the
image uses it as its Docker `HEALTHCHECK`. Both images run as UID 1000 on
`debian:bookworm-slim`.

## Loading a store

`hpds-etl load --mode <new-search|sequential> --input <csv>... --out <dir>` builds a store from
phenotype CSV. `--mode` is required: `new-search` follows `CSVLoaderNewSearch`, `sequential`
follows `SequentialLoader`. The store is encrypted with `<out>/encryption_key` unless
`--key-file` names another key. `--plain` writes an unencrypted store for benchmarking and is
refused when `<out>` holds an `encryption_key`. `--out` is refused when it holds a Java store
(`columnMeta.javabin` or `allObservationsStore.javabin`).

The CSV config is the file `--config` names, or else the first `config.json` in a directory
input or beside an input file. The log names the config applied and warns about any
`config.json` beside the inputs that is not.

Every directory the ETL creates is mode `0700` and every file `0600`, so only the owning user
can read the store or its temporary files. Both images run as UID 1000, so the `hpds-rs`
service reads what the `hpds-rs-etl` container wrote. A different service UID needs the store
files made readable to it by hand.

The store is written to a staging directory beside `--out` and moved into place when complete.
SIGTERM and SIGINT remove the staging and spill directories before exiting; a later run removes
any that a killed run left behind.

Memory: the partition pass holds the concept and path dictionaries. The build pass runs
`--threads` workers (one per core by default), each holding one partition's spill file plus
40 bytes per row of it, so peak memory is about `threads` times the largest partitions.
`--partitions` (default one per 32 MiB of input, at most 128) lowers it. These are spill
partitions, unrelated to consent partitions.

### Consent-partitioned stores

`hpds-etl load --mode partitioned --input-dir <dir> --out <dir>` follows the harmonization
branch's `CSVLoader`: every `<partition>-allConcepts.csv` in `--input-dir` becomes its own
store in `<out>/<partition>/`, all encrypted with `<out>/encryption_key` (`--key-file` installs
a copy there). The partition name is the file name up to its first `-`. Input is headerless,
rollup is always on and comes after the dataset root prefix, and a `TEXT_VALUE` that parses as
a Java double makes the row numeric. The `allConcepts` entry of the CSV config applies to every
partition. Partitions build in parallel and are published together once all are complete.

Where `CSVLoader` would crash, overwrite or hide a partition, the load is refused before
anything is written: a file without a partition prefix (a bare `allConcepts.csv`), two files
with the same prefix, a partition file in a subdirectory, and an `--out` that holds an
unpartitioned store or partitions this load does not write. Unparseable numbers and timestamps
are dropped and counted, as in the other modes; Java aborts on them.

`migrate` takes a directory of per-partition exports and writes `<out>/<partition>/`;
`bin/migrate-javabins` detects a partitioned javabin directory and exports each partition.
`dump`, `remove-concepts` and `column-meta-csv` act on every partition of a partitioned store.
`dump --out <dir>` writes `<dir>/<partition>.csv`, and `dump --merged` writes one file, the
partitions in name order under one header (with `--patients`, the union of the patient sets).
`column-meta-csv --merged` writes one `columnMeta.csv` across partitions for dictionary
hydration, merged as the Java service's `SummaryColumnMeta` merges in memory: summed counts,
unioned category values, widest width, block offsets 0. Java writes no such file. A concept
typed differently in two partitions loads into both, but `--merged` refuses it, as the query
engine refuses to open such a store.
