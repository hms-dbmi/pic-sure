# pic-sure-hpds-rs

Rust prototype of the HPDS phenotype store and query service. It is a Cargo workspace outside
the Maven reactor and is not listed in any pom.

## Crates

| Crate | Kind | Purpose |
|---|---|---|
| `hpds-format` | lib | On-disk index and block format, crypto and Java parity helpers |
| `hpds-etl` | bin + lib | Loads CSV and migrates Java javabins into the store (`hpds-etl`) |
| `hpds-query` | lib | Query engine over the consent partitions: query model, validators, cohort evaluation, result computations |
| `hpds-rs` | bin + lib | HTTP server (`hpds-rs`) over `hpds-query`, serving the harmonization branch's HPDS v3 contract |
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
| `HPDS_DATA_DIRECTORY` | `/opt/local/hpds/` | Data directory: one store per consent partition in its subdirectories, or one unpartitioned store (see [Partition layout](#partition-layout)). Matches the Java service; the all-in-one stack bind-mounts the hpds-rs host directory here. |
| `SERVER_PORT` | `8080` | Listen port on `0.0.0.0`. |
| `HPDS_CACHE_BYTES` | 4 GiB | Byte bound of the decoded block cache. The old name `CACHE_BYTES` is still read, with a warning. |
| `CACHE_SIZE` | ignored | Java's cube count; logged as ignored. |
| `HPDS_ALLOW_PLAIN` | `false` | Open a store with no key file. Local benchmarking only. |
| `HPDS_REQUIRE_AUTHORIZATION_FILTER` | `true` | Rust-only name for Java's `hpds.requireAuthorizationFilter`, which is also accepted as a variable of that exact name (the Rust name wins when both are set). Despite the name it gates `userConsents`, not authorization filters: when true, every query on `/query/sync` and `POST /query` needs at least one non-null `userConsents` value and is otherwise a 400 "User consents are required for this query", and an unpartitioned data directory is refused at startup. When false, a query without consent values reads every partition. `authorizationFilters` are validated on `POST /query` but never applied, in either mode, as on the Java branch. An open (non-auth) backend must run with it false, since the query service sends that backend no consents. |
| `ID_BATCH_SIZE` | `0` | Java's export switch. `0` ends every DATAFRAME export in ERROR with "exports are disabled (ID_BATCH_SIZE=0)"; any larger value enables exports (the batch size itself does not change the algorithm). PATIENTS is unaffected. |
| `HPDS_EXPORT_PIN_BYTES` | 2 GiB | Cap on the decoded blocks one DATAFRAME export holds in memory, summed over every selected partition. A wider export ends in ERROR with "export too wide; select fewer concepts". |
| `HPDS_MAX_SELECT_PATHS` | `5000` | Most distinct `select` paths in a query. |
| `HPDS_MAX_CLAUSE_DEPTH` | `64` | Deepest nesting of phenotypic subqueries. |
| `HPDS_MAX_CLAUSE_NODES` | `10000` | Most clauses and authorization filters in a query. |
| `HPDS_MAX_QUERY_CONCEPTS` | `20000` | Most concepts a query touches, `ANY_RECORD_OF` expansions included, counted over the union of the partitions its consents select. |
| `HPDS_MAX_CROSS_COUNT_ENTRIES` | `5000000` | Most entries in a categorical or continuous cross count. |
| `HPDS_RESULT_MAX_FILE_BYTES` | 4 GiB | Cap on one export file, checked as it is written. A larger export ends in ERROR and its partial file is deleted. |
| `HPDS_SYNC_TIMEOUT_SECONDS` | `300` | A sync query that has not answered by then, queueing included, is a 503. Its computation still finishes and then releases its slot. |
| `HPDS_RESULTS_DIRECTORY` | `/tmp/hpds-rs-results` | Export files (files 0600). A missing directory is created with mode 0700. An existing one must be a real directory (not a symlink) owned by the service user with no group or other write bit, and is never chmodded. It must not be, or lie inside, `HPDS_DATA_DIRECTORY`. Leftover export files are removed at startup. |
| `HPDS_RESULT_LIMIT` | `1000` | Most async results held at once. |
| `HPDS_RESULT_MAX_AGE_SECONDS` | unlimited | How long a finished result is kept. `0` or unset keeps it until the count or byte cap evicts it. |
| `HPDS_RESULT_MAX_BYTES` | 10 GiB | Cap on export files held at once, finished ones and ones still being written. Each write reserves its bytes first, evicting the oldest finished results to fit; an export that still does not fit beside another running export ends in ERROR. A lone result larger than the cap is kept. |
| `SMALL_JOB_LIMIT` | `100` | Exports with more `select` paths run on the large pool. |
| `SMALL_TASK_THREADS`, `LARGE_TASK_THREADS` | `1`, `1` | Exports running at once per pool. |
| `HPDS_EXPORT_CONCURRENCY` | `2` | Exports running at once across both pools; each pins its selected blocks in memory. |
| `HPDS_MAX_CONNECTIONS` | `512` | Most connections served at once. A connection accepted at the cap is answered `503` (`text/plain`, `Retry-After: 1`) and closed, so clients and the health probe fail fast instead of waiting in the kernel backlog. A request body must arrive in full within 30 seconds of the request starting (408 otherwise), and a connection idle between requests is closed after 10 seconds. |
| `HPDS_ENGINE_CONCURRENCY` | CPU count | Engine computations (sync queries and exports) running at once. |
| `HPDS_BLOCKING_THREADS` | `64`, or the engine concurrency plus 16 when that is larger | Cap on tokio's blocking threads. Startup fails unless `HPDS_ENGINE_CONCURRENCY` is below it, so file reads keep some blocking threads. |
| `RUST_LOG` | `info` | Log filter, in `tracing` `EnvFilter` syntax. The `hpds_rs::access` target logs one line per request at info with method, path, status and latency. For benchmarks use `RUST_LOG=info,hpds_rs::access=warn` to silence it. |
| `HPDS_LOG_DIRECTORY` | unset | When set, the log is also written to a daily-rotated `hpds-rs.<date>.log` in that directory, so a mounted log directory is useful. Logging goes through a non-blocking writer; under extreme load it drops lines rather than slowing requests. |

A query over a limit is a 400 naming the setting, before any block is read. Checks run in
this order on both query routes: the query's shape (select paths, clause depth and nodes), then
its consents, then the concepts it touches in the partitions those consents select, then (on
`POST /query` only) `QueryValidator`.

The key is read from `<HPDS_DATA_DIRECTORY>/encryption_key`. When the data directory cannot be
opened (no partition, a partition left unmigrated, a `.generation` mismatch, a concept typed
differently in two partitions, an unpartitioned store while consents are required) the server
still starts, logs the open error, health reports DOWN with a reason, and query routes answer
503.

### Partition layout

The service opens `HPDS_DATA_DIRECTORY` as `PartitionedPhenotypicObservationStore` does:

- When the directory itself holds `pheno.index`, it is one unpartitioned store, read whatever
  the consents. It is refused while `HPDS_REQUIRE_AUTHORIZATION_FILTER` is true, as Java
  refuses an unpartitioned `allObservationsStore.javabin`.
- Otherwise every immediate subdirectory holding `pheno.index` is a consent partition named
  after the subdirectory, for example `<dir>/phs000007.c1/`. A query's `userConsents` values
  select the partitions of the same names; values naming no partition are skipped, so a query
  whose every consent is unknown counts 0. The rules for which subdirectories count are those
  of [Consent-partitioned stores](#consent-partitioned-stores); a subdirectory with Java
  javabins but no `pheno.index` refuses the open rather than dropping that consent group.
- Every partition shares `<dir>/encryption_key` and one block cache of `HPDS_CACHE_BYTES`.
- When `<dir>/.generation` exists (the partitioned ETL writes it last), the partitions' store
  ids must match it exactly, or the open is refused as a load still being published.
- A concept typed numeric in one partition and categorical in another refuses the open, naming
  the concept and both partitions.

`/search`, validation and health read the merged dictionary of every partition, whatever the
consents, as Java's `SummaryColumnMeta` map does: category values unioned, the widest width,
min of mins and max of maxes, and patient counts summed.

### Routes

Mounted under `/PIC-SURE/v3` and under the unversioned `/PIC-SURE`. The Java branch serves only
`/PIC-SURE/v3`, but its query service still posts search to `/PIC-SURE/search`, which the Java
service answers 404, until the change that sends every HPDS call through `/v3` lands. Request bodies are capped at 2 MiB, a body that stalls for 30 seconds is cut
off, and a connection that takes more than 10 seconds to send a request's headers is closed.

| Route | Answer |
|---|---|
| `POST /query/sync` | COUNT (`text/plain`) and the four cross counts (`application/json`), byte for byte as Java, with a `queryMetadata` header, over the partitions `userConsents` selects; INFO_COLUMN_LISTING is `[]` |
| `POST /query` | `QueryStatus` for a PATIENTS or DATAFRAME export over the partitions `userConsents` selects; DATAFRAME_TIMESERIES and DATAFRAME_PFB end in ERROR |
| `POST /query/{id}/status` | `QueryStatus`, with Java's status mapping |
| `POST /query/{id}/result` | The CSV export as `text/plain`; 400 `Status : <state>` before it succeeds |
| `POST /query/{id}/signed-url` | 400, S3 upload is out of scope |
| `POST /query/format`, `POST /write/{dataType}` | 400, not supported |
| `POST /search` | The merged dictionary as `SummaryColumnMeta` (`name`, `widthInBytes`, `categorical`, `categoryValues`, `min`, `max`, `patientCount`, `timestampMin`, `timestampMax`, nulls written), not consent-scoped. A string term, `""` included, answers an object keyed by path, matched by case-insensitive path substring or exact category value; a null or missing term answers Java's array of one-entry objects for every concept. `info: {}`. See the locale note in `search.rs`. |
| `GET /search/values/` | An empty page |
| `GET /actuator/health` | 200 `{"status":"UP"}` when the merged dictionary of the partitions holds a concept, else 503 `{"status":"DOWN","reason":...}` |

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
- With `HPDS_REQUIRE_AUTHORIZATION_FILTER` true, a query with no consent value is a 400 "User
  consents are required for this query" on both query routes. Java answers `/query/sync` 200
  with an empty body and no `queryMetadata` header, and `POST /query` 200 with a status of
  ERROR.
- `/query/sync` does not run `QueryValidator`, as Java does not, so a filter on a concept no
  partition holds counts 0. `POST /query` validates as Java does, but refuses with a 400
  instead of registering an ERROR result.
- Every route is also served without the `/v3` segment (see above); Java answers those 404.
- Request bodies holding the same key twice in any object, the envelope, the query, a clause
  or a `userConsents` entry included, and in a `query` sent as a string, are a 400. Jackson
  keeps the last copy.
- `/search` writes concepts in path order and `categoryValues` sorted. Java's order of both is
  unspecified (an immutable map whose order changes with every start, a `HashMap`, a
  `HashSet`). `timestampMin` and `timestampMax` are always `null`, as no Java loader sets
  them; the Rust loaders do record them.
- The data directory opens or refuses as a whole: a partition left in javabin form, a
  `.generation` mismatch, or a concept typed differently in two partitions keeps the service
  DOWN. Java has neither of the first two checks and fails the third only at query time.
- Result ids and `queryMetadata` are random UUIDs, not Java's UUIDv5 of the query text.
- A failed query's `resultMetadata` carries an `error` message.
- Results are dropped oldest finished first when `HPDS_RESULT_LIMIT` or `HPDS_RESULT_MAX_BYTES`
  is reached, and after `HPDS_RESULT_MAX_AGE_SECONDS` when that is set; Java keeps them until
  restart.
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
input or beside an input file; `--no-config` applies none. The log names the config applied and
warns about any `config.json` beside the inputs that is not. Java's loaders read
`/opt/local/hpds_input/config.json` whatever their inputs, which in the all-in-one is
`hpds_input/config.json` on the host. To reproduce a Java load, pass that file with `--config`.

Every directory the ETL creates is mode `0700` and every file `0600`, so only the owning user
can read the store or its temporary files. Both images run as UID 1000, so the `hpds-rs`
service reads what the `hpds-rs-etl` container wrote. A different service UID needs the store
files made readable to it by hand.

The store is written to a staging directory beside `--out` and moved into place when complete.
The staging directory's name carries the pid, a per-process sequence number and a random
suffix, so concurrent runs, also in separate containers, never share one. Moving into place is
a `rename`, which cannot cross filesystems, so an existing `--out` that is a mount point (on
another filesystem than its parent) is refused before anything is built; give a directory
inside it instead. SIGTERM and SIGINT remove the staging and spill directories before exiting;
a later run removes any that a killed run left behind. A live staging or spill directory holds
an exclusive `flock` on a `.lock` file inside it, and a run removes a leftover only after taking
that lock, so concurrent runs in other containers sharing the parent directory never lose a
live one. The lock file is removed before a staging directory is moved into place.

Memory: the spill routing pass holds the concept and path dictionaries. The build pass runs
`--threads` workers (one per core by default), each holding one spill file plus 40 bytes per
row of it, so peak memory is about `threads` times the largest spill files.
`--spill-partitions` (default one per 32 MiB of input, at most 128) lowers it. These spill
files are unrelated to consent partitions; `--partitions` is still accepted as a deprecated
name for the flag.

Where the Rust loader reads input Java would refuse, it keeps going and counts what it did in
`etl-report.json`:

- An unparseable number drops its row (`dropped_unparseable_number`). An unparseable timestamp
  keeps the row and drops only the timestamp (`unparseable_timestamps`). `CSVLoaderNewSearch`
  and `CSVLoader` abort the load on either.
- A UTF-8 byte order mark at the start of a file is skipped (`inputs_with_byte_order_mark`).
  Commons CSV keeps it in the first field: harmless in a header line, but in headerless
  partitioned input it makes the first patient number unparseable and `CSVLoader` aborts.
- Text after a quoted field's closing quote, as in `"a"b`, is kept as part of the field
  (`records_with_text_after_closing_quote`). Commons CSV fails with "invalid char between
  encapsulated token and delimiter".

Both of the last two are also logged once per file at warn. One difference concerns the
config rather than the input: Java's `ConfigLoader` strips `.csv` from the config's keys while
iterating the map, so a `config.json` with two or more `.csv` keys throws
`ConcurrentModificationException` after the first. The exception is logged and the rest keep
their suffix, so lookups under those names find nothing. The Rust loader strips the suffix from
every key.

### Consent-partitioned stores

`hpds-etl load --mode partitioned --input-dir <dir> --out <dir> (--config <file> | --no-config)`
follows the harmonization branch's `CSVLoader`: every `<partition>-allConcepts.csv` in
`--input-dir` becomes its own store in `<out>/<partition>/`, all encrypted with
`<out>/encryption_key` (`--key-file` installs a copy there). The partition name is the file name
up to its first `-`. Input is headerless, rollup is always on and comes after the dataset root
prefix, and a `TEXT_VALUE` that parses as a Java double makes the row numeric. The
`allConcepts` entry of the CSV config applies to every partition, and a config without one is
loaded with a warning. Partitioned mode needs `--config` or `--no-config` and does not look
beside the inputs, because `CSVLoader` reads `/opt/local/hpds_input/config.json` and not its
input directory: give `--config <hpds_input>/config.json` to match a Java load that had one.

Partitions build in parallel and nothing is published until every one is complete; a failure
in any partition publishes none. Into a new `--out` the whole set appears at once. Into an
existing one the publish is not atomic across partitions: the partitions are replaced one after
another (each partition's `pheno.index` last), so a reader can briefly see old and new
partitions side by side. The last step writes `<out>/.generation` (mode `0600`, through a
temporary file renamed into place): one line per partition, in partition name order, holding
the store id of its `pheno.index` as 32 lowercase hex digits. The query service reads the first
token of each non-blank line and refuses to open the store while those ids differ from the
partitions' indexes, so a publish under way or interrupted is never served.

Where `CSVLoader` would crash, overwrite or hide a partition, the load is refused before
anything is written: a file without a partition prefix (a bare `allConcepts.csv`), two files
with the same prefix, a partition file in a subdirectory, and an `--out` that holds an
unpartitioned store, partitions this load does not write, or a subdirectory the partition rule
below refuses.

Every command that reads a partitioned directory finds its partitions by one rule, which the
query service applies too:

- only immediate subdirectories count, and names starting with `.` are ignored;
- a subdirectory holding `pheno.index` is a partition;
- one holding `allObservationsStore.javabin` and `columnMeta.javabin` but no `pheno.index` is
  an error naming it, so an unconverted Java partition is never silently left out;
- one holding only one of the two javabin files is an error naming it;
- any other subdirectory is ignored.

`migrate` applies the same rule to a directory of per-partition exports, with `concepts.bin` in
place of `pheno.index`, and writes `<out>/<partition>/` and `.generation`.
`bin/migrate-javabins` detects a partitioned javabin directory and exports each partition: a
subdirectory with both javabin files is a partition, one with only one of them fails the run.

`dump`, `remove-concepts` and `column-meta-csv` act on every partition of a partitioned store.
`remove-concepts` changes the partitions one at a time; when one fails, the error names it and
the partitions already changed. Rerunning is safe: the changed partitions report the concepts
as not found and the rest have them removed.

`dump --out <dir>` writes `<dir>/<partition>.csv`, and `dump --concat` (formerly `--merged`,
still accepted) writes one file, the partitions' rows concatenated in name order under one
header (with `--patients`, the union of the patient sets). Dumps hold observation data in the
clear, so output files are created mode `0600` without following a symbolic link, and an
existing file is refused unless `--force`, which replaces it through a temporary file renamed
over it. A new `--out` directory is created mode `0700`; an existing one must be a real
directory that grants group and others nothing. No `--out` may lie in the store or in any of
its partition directories, wherever a symbolic link takes them. The same applies to a
single-store dump.

`column-meta-csv --merged` is a different operation from `dump --concat`: it writes one
`columnMeta.csv` with one row per concept, its metadata combined across partitions for
dictionary hydration, as the Java service's `SummaryColumnMeta` merges in memory: summed
counts, unioned category values, widest width, block offsets 0. Java writes no such file. A
concept typed differently in two partitions loads into both, but `--merged` refuses it, as the
query engine refuses to open such a store.
