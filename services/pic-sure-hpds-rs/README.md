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
| `HPDS_DATA_DIRECTORY` | `/opt/local/hpds/` | Data directory: one store, partitioned by consent inside its blocks or unpartitioned (see [Partition layout](#partition-layout)). Matches the Java service; the all-in-one stack bind-mounts the hpds-rs host directory here. |
| `SERVER_PORT` | `8080` | Listen port on `0.0.0.0`. |
| `HPDS_CACHE_BYTES` | 4 GiB | Byte bound of the decoded block cache. One cache entry holds a concept's block and, once a narrow selection has read the concept through it, its partition view (see [Narrow selections](#narrow-selections)), weighed together, so a read of one concept costs one lookup whatever the selection. The old name `CACHE_BYTES` is still read, with a warning. |
| `CACHE_SIZE` | ignored | Java's cube count; logged as ignored. |
| `HPDS_ALLOW_PLAIN` | `false` | Open a store with no key file. Local benchmarking only. |
| `HPDS_RESULT_CACHE_BYTES` | `0` (off) | Byte bound of the sync result cache, which holds COUNT, CROSS_COUNT, CATEGORICAL_CROSS_COUNT, CONTINUOUS_CROSS_COUNT and OBSERVATION_CROSS_COUNT answers (see [Result cache](#result-cache)). `0` turns it off. A bound above 0 but below 2,864 bytes can store no answer, since one answer may take at most a sixteenth of it; startup logs a warning saying so. |
| `HPDS_COMBINED_STORE`, `HPDS_PER_PARTITION_COUNTS` | ignored | Settings of the format 1 store layout; logged as ignored. |
| `HPDS_REQUIRE_AUTHORIZATION_FILTER` | `true` | Rust-only name for Java's `hpds.requireAuthorizationFilter`, which is also accepted as a variable of that exact name (the Rust name wins when both are set). Despite the name it gates `userConsents`, not authorization filters: when true, every query on `/query/sync` and `POST /query` needs at least one non-null `userConsents` value and is otherwise a 400 "User consents are required for this query", and an unpartitioned data directory is refused at startup. When false, a query without consent values reads every partition. `authorizationFilters` are validated on `POST /query` but never applied, in either mode, as on the Java branch. An open (non-auth) backend must run with it false, since the query service sends that backend no consents. |
| `ID_BATCH_SIZE` | `0` | Java's export switch. `0` ends every DATAFRAME export in ERROR with "exports are disabled (ID_BATCH_SIZE=0)"; any larger value enables exports (the batch size itself does not change the algorithm). PATIENTS is unaffected. |
| `HPDS_EXPORT_PIN_BYTES` | 2 GiB | Cap on the decoded blocks one DATAFRAME export holds in memory, summed over the blocks it pins. Each concept's whole block counts, every partition's rows, whatever the selection, since that is what the export holds. A wider export ends in ERROR with "export too wide; select fewer concepts". |
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
| `HPDS_RESULT_MAX_BYTES` | 10 GiB | Cap on export results held at once, in files and in memory, finished ones and ones still being written. Each write reserves its bytes first, evicting the oldest finished results to fit; an export that still does not fit beside another running export ends in ERROR. A lone result larger than the cap is kept. |
| `HPDS_RESULT_MEMORY_BYTES` | 64 MiB | Budget of the export results held in memory instead of a file. An export of at most 256 KiB is kept in memory while the budget has room for it, and written to a file otherwise. Held results and downloads still in progress count against it. `0` writes every result to a file. Clients see no difference: status, retention and the `/result` response are the same either way. |
| `SMALL_JOB_LIMIT` | `100` | Exports with more `select` paths run on the large pool. |
| `SMALL_TASK_THREADS`, `LARGE_TASK_THREADS` | `1`, `1` | Exports running at once per pool. |
| `HPDS_EXPORT_CONCURRENCY` | `2` | Exports running at once across both pools; each pins its selected blocks in memory. |
| `HPDS_MAX_CONNECTIONS` | `512` | Most connections served at once. A connection accepted at the cap is answered `503` (`text/plain`, `Retry-After: 1`) and closed, so clients and the health probe fail fast instead of waiting in the kernel backlog. A request body must arrive in full within 30 seconds of the request starting (408 otherwise), and a connection idle between requests is closed after 10 seconds. |
| `HPDS_ENGINE_CONCURRENCY` | CPU count | Engine computations (sync queries and exports) running at once. One slot may use several cores, since a computation fans out across concepts and clauses with rayon. |
| `HPDS_BLOCKING_THREADS` | `64`, or the engine concurrency plus 16 when that is larger | Cap on tokio's blocking threads. Startup fails unless `HPDS_ENGINE_CONCURRENCY` is below it, so file reads keep some blocking threads. |
| `RUST_LOG` | `info` | Log filter, in `tracing` `EnvFilter` syntax. The `hpds_rs::access` target logs one line per request at info with method, path, status and latency. For benchmarks use `RUST_LOG=info,hpds_rs::access=warn` to silence it. |
| `HPDS_LOG_DIRECTORY` | unset | When set, the log is also written to a daily-rotated `hpds-rs.<date>.log` in that directory, so a mounted log directory is useful. Logging goes through a non-blocking writer; under extreme load it drops lines rather than slowing requests. |
| `HPDS_SEARCH_GZIP` | `off` | `on` compresses `POST /search` answers for requests whose `Accept-Encoding` accepts gzip (see [Search compression](#search-compression)). Off, every answer has the bytes and headers it had before compression existed, whatever the request accepts. Read like `HPDS_METRICS`. |
| `HPDS_METRICS` | `off` | `on` serves `GET /metrics` in the Prometheus text format and counts requests (see [Metrics](#metrics)). Off, `/metrics` is a 404 like any unknown path. `true`, `false`, `1`, `0`, `yes` and `no` are also read; anything else fails startup. |

A query over a limit is a 400 naming the setting, before any block is read. Checks run in
this order on both query routes: the query's shape (select paths, clause depth and nodes), then
its consents, then the concepts it touches in the partitions those consents select, then (on
`POST /query` only) `QueryValidator`.

The key is read from `<HPDS_DATA_DIRECTORY>/encryption_key`. When the data directory cannot be
opened (no store, a store of format 1 written by Rust HPDS v1 to v3, a partition left in
javabin form, a `.generation` mismatch, a block that disagrees with the index, an
unpartitioned store while consents are required) the server still starts, logs the open
error, health reports DOWN with a reason, and query routes answer 503. The startup log line
`store opened` carries `overlap_patients`, the size of the overlap set (see
[Partition layout](#partition-layout)). At shutdown the service logs what its block cache
holds: blocks, partition views, and the bytes of each.

### Partition layout

The service opens the store in `HPDS_DATA_DIRECTORY` (`pheno.index` and `pheno.blocks` in the
directory itself) as `PartitionedPhenotypicObservationStore` serves its partitions:

- A store whose index records consent partitions is partitioned. A query's `userConsents`
  values select the partitions of the same names; values naming no partition are skipped,
  so a query whose every consent is unknown counts 0. A store without partitions is read
  whatever the consents, and is refused while `HPDS_REQUIRE_AUTHORIZATION_FILTER` is true,
  as Java refuses an unpartitioned `allObservationsStore.javabin`.
- Each concept is one block holding every partition's rows, in the order the v2 engine merged
  partitions: by patient, then partition name in Java string order, then each partition's own
  order. The index records which partitions hold the concept, with their row and patient
  counts, each partition's patient set, and the overlap patients whose rows are tagged with
  each partition. A patient in exactly one partition's set decides the partition of all their
  rows; the patients that do not (in two or more sets, or with a row in a partition whose set
  lacks them) form the overlap set, and every block holding one of their rows records each
  row's partition. A patient with rows but in no partition's set (a migrated partition whose
  Java patient list misses them) is in the overlap set but not in the store's patients, which
  stay the union of the partition sets, as Java's patient ids do. A block whose partition
  record disagrees with the index or the patient sets refuses to load (`QueryError::Block`).
- A store of format 1, written by Rust HPDS v1 to v3, is refused naming
  `hpds-etl convert` (see [Converting a store of format 1](#converting-a-store-of-format-1)):
  a format 1 `pheno.index`, or a directory without one but with partition subdirectories
  holding one or a `.combined` directory. A subdirectory with Java javabins but no
  `pheno.index`, in a directory without a store, refuses the open naming `hpds-etl migrate`.
  Beside a store, subdirectories are ignored.
- When `<dir>/.generation` exists (the ETL writes it last), it must hold exactly the store id
  of `pheno.index`, or the open is refused as a load still being published.
- A filter matches a patient with a satisfying row in any selected partition, before
  filters combine, so a patient in two partitions can match one filter in one and another
  filter in the other, as in Java.

`/search`, validation and health read the store's dictionary, whatever the consents, merged
over the partitions as Java's `SummaryColumnMeta` map merges them: category values unioned,
the widest width, min of mins and max of maxes, and patient counts summed.

### Narrow selections

A query whose consents select every partition (every partition named, unknown values
beside them skipped as ever, or no consent value with `HPDS_REQUIRE_AUTHORIZATION_FILTER`
false) reads each concept's block and answers from it directly. So does every query on an
unpartitioned store. A narrower selection reads the same blocks and keeps only what its
partitions hold. The answers are byte for byte those of a store holding only the selected
partitions.

- **Routed patients.** A patient outside the overlap set has every row in the one partition
  their patient set names, so a filter evaluated on the whole block is right for them once
  patients of unselected partitions are dropped. A clause is evaluated on whole blocks, with
  the overlap patients' share of each filter corrected by tag, and intersected once, at the
  end, with the selected partitions' patients outside the overlap set together with the
  overlap set; no clause negates, so that is the same as intersecting every filter.
- **Concept by concept.** When the selected partitions hold every row of a concept, its
  block is read as for a full selection. When they hold at least `NARROW_VIEW_SHARE` of its
  rows (0.75), the block is evaluated whole. Below that share the concept's partition view
  is read: the block's rows grouped by partition (value positions per partition for a
  numeric concept, patients per partition, and patients per category and partition), built
  from the block on first use and cached with it, so the work follows the selected rows. A
  filter read from a view needs no final intersection, nor does an AND holding one.
- **Overlap patients.** Rows of the overlap set's patients carry their partition, and every
  filter, count and export reads those rows by tag, keeping only rows of selected
  partitions. When the overlap set is empty no tag is ever read.
- **Cohort check.** Before any count or export uses a cohort, on every selection including a
  full one, the engine checks that every patient in it is in a selected partition: in its
  patient set, or with a row tagged with it, as the index records for each partition. A patient
  outside fails the query with a 500 and an error log that counts such patients without naming
  any. Only a fault in the engine can trip it; it turns such a fault into an error instead of
  an answer from another partition's patients.
- **Repeated selections.** The union of the selected partitions' patient sets is computed
  once per distinct selection and kept, up to 64 MiB of such sets.

### Result cache

An opt-in cache of sync answers, off unless `HPDS_RESULT_CACHE_BYTES` is above 0.

- **What it holds.** The rendered answers of COUNT, CROSS_COUNT, CATEGORICAL_CROSS_COUNT,
  CONTINUOUS_CROSS_COUNT and OBSERVATION_CROSS_COUNT on `POST /query/sync`. Not exports
  (`POST /query`), async results, errors, or a cross count holding -1 for a block that
  could not be loaded.
- **When it is read.** Only after the query passes every check a computed query passes:
  limits, genomic input, then consents. A query that would be refused is refused with the
  same message whether or not an answer to a similar query is cached. A hit skips the
  engine queue and answers at once.
- **Keying.** An entry is keyed on the store generation (a hash of the store id), the result
  type, the partitions the consents select after admission, and the fields that decide the
  answer: the phenotypic clause, and `select` for CROSS_COUNT and OBSERVATION_CROSS_COUNT. It
  also holds `authorizationFilters` as their exact canonical JSON, filters and values in
  request order with repeats kept, nothing sorted or deduplicated. They are never applied, so
  what their order or repetition would mean is undefined; two queries that differ there in any
  way, order included, compute the same answer into two entries, which costs hits but is never
  wrong. The key holds the whole encoding, not a hash of it. `id`, `picsureId` and the consent
  values themselves are not in it, so two queries whose consents select different partitions
  never share an entry, while consent lists that select the same partitions (reordered,
  repeated, or with unknown values) do.
- **Lifetime.** The cache is in the process's memory and dies with it. The service opens
  its stores only at startup, so a new load is served only after a restart, which starts
  with an empty cache.
- **Answers.** A cached answer is the same bytes with the same `Content-Type`, and a fresh
  `queryMetadata` UUID as every answer gets.
- **Bound.** The entries' summed weight (key, body and 128 bytes of overhead each) is kept
  within `HPDS_RESULT_CACHE_BYTES` by the same `moka` TinyLFU policy as the block cache,
  which applies evictions in batches, so the total can pass the bound briefly between
  them. An answer heavier than a sixteenth of the bound is not cached.
- **Counters.** With the cache on, the `hpds_rs::result_cache` log target reports hits,
  misses, the hit rate, entries, bytes and the bound every 60 seconds in which a lookup
  happened, and once at shutdown. With `HPDS_METRICS=on`, `/metrics` reports them too, with
  evictions. Health does not report them.
- **What to expect.** An ordinary query already answers in well under a millisecond on a
  warm block cache, so a hit mostly saves heavy entries (study-level `ANY_RECORD_OF`, wide
  cross counts). The `hpds-bench` corpora replay about a hundred distinct sync queries in a
  loop, so their hit rate converges on 100%; treat cache-on benchmark numbers as a best
  case, not a forecast for real traffic.

### Search compression

Off by default. While `HPDS_SEARCH_GZIP` is on, `POST /search` answers gzip only when the
request's `Accept-Encoding` accepts it, following RFC 9110: `gzip` or `x-gzip` with a qvalue
above 0, or `*` when gzip is not named; names compare case-insensitively, `gzip;q=0` refuses
it whatever `*` says, and an unparsable qvalue counts as 0. Such a request gets a body of at
least 1,400 bytes as `Content-Encoding: gzip`, with `Content-Length` of the compressed bytes,
which decompress to exactly the uncompressed body.
Any request that sends `Accept-Encoding` gets `Vary: Accept-Encoding` on a body of that size,
compressed or not. A shorter body is never compressed: the gzip stream's setup cost buys
nothing on a body that already fits in about one TCP segment with its headers. A request
without `Accept-Encoding` gets the same bytes and headers as before compression existed. No
other route compresses.

- **Levels.** A body matched for a term is compressed at level 1 on every request, the
  fastest, about 7 to 9 times smaller on `/search` bodies; that is still about 8 ms of CPU
  per 6 MB answer on the bench store, paid again by each request for it. Level 3 is a third smaller again but costs half again the
  CPU, which pays only on a link slower than about 450 Mbit/s. The two answers that hold every
  concept (a null or missing term, and the empty term) are built once at startup, shared by
  every request, and compressed at level 9 the first time a client asks, then kept.
- **The query service asks.** The harmonization query service talks to HPDS through Apache
  HttpClient 5, which sends `Accept-Encoding: gzip, deflate, x-gzip` on every request and
  decompresses the body, so its `/search` calls get gzip. Its decoded `SearchResults` are the
  same. Decoding costs it about 1 ms per MB of JSON; on a link faster than a few Gbit/s
  between the two services, compressing and decoding take longer than sending the body
  plain, which is why compression is off by default. Turn it on for a slow link between
  the services.
- **Why not query answers or downloads.** HttpClient 5 leaves `Content-Encoding` and the
  compressed `Content-Length` on the response it decompresses, and the query service returns
  HPDS's `/query/{id}/result` response to its caller headers and all, so a compressed download
  would reach the caller as plain text labeled gzip with the wrong length. Sync answers are
  mostly under 300 bytes.

### Metrics

With `HPDS_METRICS=on`, `GET /metrics` reports these in the Prometheus text exposition format.
Labels take only fixed values: route templates as the router declares them (or `unmatched`),
result type names (or `none`), error kinds, histogram bounds and export outcomes. No label or
value carries a patient id, concept path, consent name, result id or anything from a request
body. Counters are relaxed atomic adds; the block cache's lookup counters are striped across
cache lines. Reading the block cache walks it to weigh the partition views, so a scrape runs on
the blocking pool.

| Metric | Type | Meaning |
|---|---|---|
| `hpds_http_requests_total{route,result_type}` | counter | Requests answered, by route template and, on `/query/sync` and `POST /query`, the result type the query named |
| `hpds_http_request_duration_seconds{route}` | histogram | Time from receiving a request to sending its response head; a streamed download's body is not timed. Bounds 0.5 ms to 60 s |
| `hpds_http_errors_total{kind}` | counter | 4xx and 5xx answers: `bad_request`, `not_found`, `request_timeout`, `payload_too_large`, `internal`, `unavailable`, `other_client`, `other_server` |
| `hpds_connections_refused_total` | counter | Connections answered 503 at `HPDS_MAX_CONNECTIONS` |
| `hpds_block_cache_hits_total`, `hpds_block_cache_misses_total` | counter | Block cache lookups, one per concept read |
| `hpds_block_cache_evictions_total` | counter | Entries dropped (or never admitted) to stay within `HPDS_CACHE_BYTES` |
| `hpds_block_cache_entries`, `hpds_block_cache_bytes`, `hpds_block_cache_capacity_bytes` | gauge | Concepts and bytes held, blocks and views, and the bound |
| `hpds_block_cache_views`, `hpds_block_cache_view_bytes` | gauge | Partition views held and their bytes, part of the above |
| `hpds_block_loads_total`, `hpds_block_load_seconds_total` | counter | Blocks read from the store, authenticated and decoded, and the seconds spent, summed over threads |
| `hpds_cohort_guard_refusals_total` | counter | Queries refused by the cohort check (see [Narrow selections](#narrow-selections)) |
| `hpds_result_cache_hits_total`, `_misses_total`, `_evictions_total` | counter | Result cache lookups and evictions; only with `HPDS_RESULT_CACHE_BYTES` above 0 |
| `hpds_result_cache_entries`, `_bytes`, `_capacity_bytes` | gauge | What the result cache holds, and its bound; only when it is on |
| `hpds_async_results` | gauge | Async results held, pending, running and finished |
| `hpds_exports_finished_total{outcome}` | counter | Async queries finished: `memory`, `file` or `error` (refusals at submission included) |
| `hpds_result_memory_bytes`, `hpds_result_memory_budget_bytes` | gauge | Export results held in memory, downloads in progress included, and `HPDS_RESULT_MEMORY_BYTES` |

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
| `POST /search` | The merged dictionary as `SummaryColumnMeta` (`name`, `widthInBytes`, `categorical`, `categoryValues`, `min`, `max`, `patientCount`, `timestampMin`, `timestampMax`, nulls written), not consent-scoped. A string term, `""` included, answers an object keyed by path, matched by case-insensitive path substring or exact category value; a null or missing term answers Java's array of one-entry objects for every concept. `info: {}`. See the locale note in `search.rs`. gzip on request, see [Search compression](#search-compression). |
| `GET /search/values/` | An empty page |
| `GET /actuator/health` | 200 `{"status":"UP"}` when the store's dictionary holds a concept; else 503 `{"status":"DOWN","reason":...}` |
| `GET /metrics` | With `HPDS_METRICS=on`, the counters of [Metrics](#metrics) as Prometheus text (`text/plain; version=0.0.4`); otherwise 404. At the root, not under `/PIC-SURE` |

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
- The data directory opens or refuses as a whole: a partition left in javabin form or a
  `.generation` mismatch keeps the service DOWN. Java has neither check. A concept typed
  differently in two partitions never reaches the service: the ETL refuses to load it, where
  Java loads it and fails only at query time.
- Result ids and `queryMetadata` are random UUIDs, not Java's UUIDv5 of the query text.
- A failed query's `resultMetadata` carries an `error` message.
- With the consent flag on and an unpartitioned data directory, Java fails to start; this
  service starts and reports DOWN on `/actuator/health`.
- Neither service binds an async result to the consents it was submitted with. The query
  service enforces read access (`verifyReadAccess`) before it hands out a result, as it does
  for Java.
- Java seeks the minimum index per partition (`seekForMinIndex`), so a range filter whose
  `min` is above every value of some selected partition answers an empty 200 there. This
  service answers normally.
- `VARIANT_COUNT_FOR_QUERY` with no `genomicFilters` is a 200 `{"count":"0","message":"No
  variant filters were supplied, so no query was run."}` with a `queryMetadata` header, as in
  Java. Java runs no consent check for it; this service applies its own, so with the flag on
  and no consent value it is the 400 "User consents are required for this query". With
  `genomicFilters` it is a 400 unsupported.
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

The store is written to a staging directory beside `--out` and moved into place when complete,
with `<out>/.generation` (mode `0600`, through a temporary file renamed into place) written
last: one line, the store id of its `pheno.index` as 32 lowercase hex digits. Into an existing
`--out`, `pheno.blocks`, `columnMeta.csv` and `etl-report.json` are renamed over the old files
and `pheno.index` last, so a reader that finds the new index finds the new blocks, and the
marker names the old id until the new store is complete. That publish holds an exclusive
`flock` on the `--out` directory itself (no lock file), the store lock, which every command
that writes a store takes (`load`, `migrate`, `convert`, `remove-concepts`, `column-meta-csv`),
so two of them never interleave, also from separate containers sharing the directory (on one
kernel, see below). `--out` is refused when it holds a partitioned store of format 1 (partition
directories or a `.combined` directory); convert that one into another directory instead.
The staging directory's name carries the pid, a per-process sequence number and a random
suffix, so concurrent runs, also in separate containers, never share one. Moving into place is
a `rename`, which cannot cross filesystems, so an existing `--out` that is a mount point (on
another filesystem than its parent) is refused before anything is built; give a directory
inside it instead. SIGTERM and SIGINT remove the staging and spill directories before exiting;
a later run removes any that a killed run left behind. A live staging or spill directory holds
an exclusive `flock` on a `.lock` file inside it, and a run removes a leftover only after taking
that lock, so concurrent runs in other containers sharing the parent directory never lose a
live one. The lock file is removed before a staging directory is moved into place.

Every `flock` here serializes processes under one kernel only: processes on one host,
containers sharing that host's kernel included. It does not serialize processes on
different hosts, nor on a network filesystem (NFS, EFS, SMB), where a lock taken on one
client may not be seen on another. A host ETL and a container ETL on a Docker Desktop bind
mount also run under different kernels. Running two loads against one store from
different kernels is not supported.

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
`--input-dir` becomes a consent partition of one store in `<out>`, encrypted with
`<out>/encryption_key` (`--key-file` installs a copy there). The partition name is the file name
up to its first `-`. Input is headerless, rollup is always on and comes after the dataset root
prefix, and a `TEXT_VALUE` that parses as a Java double makes the row numeric. The
`allConcepts` entry of the CSV config applies to every partition, and a config without one is
loaded with a warning. Partitioned mode needs `--config` or `--no-config` and does not look
beside the inputs, because `CSVLoader` reads `/opt/local/hpds_input/config.json` and not its
input directory: give `--config <hpds_input>/config.json` to match a Java load that had one.

Each partition is built in parallel with those rules, its type fixed by its own first row and
its own drops counted, into a store of its own inside the staging directory. The staged stores
are then merged into the one store and removed: each concept's block holds the rows of every
partition holding it, merged by patient, ties in partition name order (Java string order, which
differs from byte order only for names with characters above U+FFFF), each partition's rows in
its own order. The index records each partition's patient set, the partitions holding each
concept with their row and patient counts, and the overlap set (see
[Partition layout](#partition-layout)). Nothing is published until the merged store is
complete; a failure in any partition publishes nothing. The store is published as above.

A concept typed numeric in one partition and categorical in another fails the load, naming the
concept and both partitions: one block cannot hold both types. Java loads both and fails when a
query reads the concept.

`etl-report.json` holds each partition's counts (for its staged store) under `partitions`, and
under `store` what the merge wrote: the store id, each partition's patient count and
`patients-indexed` record, concepts, rows and patients, the overlap patients, their rows and
that share of all rows, the tagged concepts, block bytes and seconds. The load logs a warning
when the overlap rows exceed 5% of the rows, since narrow consent selections read those rows by
tag, and another naming any partition whose rows hold a patient its patient set lacks.

Where `CSVLoader` would crash, overwrite or hide a partition, the load is refused before
anything is written: a file without a partition prefix (a bare `allConcepts.csv`), two files
with the same prefix, and a partition file in a subdirectory.

`migrate` reads a directory of per-partition exports the same way, one partition per
immediate subdirectory holding `concepts.bin` (names starting with `.` ignored; a subdirectory
holding only one of the two javabin files, or both but no export, is an error naming it), and
merges the migrated partitions into one store as `load` does. A partition carries
`patients-indexed` only when every patient with an observation is in its exported patient
list; the others go into the overlap set. `bin/migrate-javabins` detects a partitioned javabin
directory and exports each partition: a subdirectory with both javabin files is a partition,
one with only one of them fails the run.

`remove-concepts` and `column-meta-csv` act on the one store under its store lock.
`remove-concepts` keeps the store id, the partition table and the overlap set: removing rows
never puts an overlap patient into an untagged block.

`dump --out <dir>` of a partitioned store writes `<dir>/<partition>.csv`, each holding the rows
of that partition (its tags, or its patients' routing), concept by concept, in the order a
store of that partition alone dumps them; `dump --concat` (formerly `--merged`, still accepted)
writes one file, those partitions' rows concatenated in byte order of the names under one
header (with `--patients`, the store's patient set, the union of the partitions'). Splitting
reads every block once, through a temporary directory (mode `0700`) beside the output, removed
afterwards. Dumps hold observation data in the clear, so output files are created mode `0600`
without following a symbolic link, and an existing file is refused unless `--force`, which
replaces it through a temporary file renamed over it. A new `--out` directory is created mode
`0700`; an existing one must be a real directory that grants group and others nothing. No
`--out` may lie in the store, wherever a symbolic link takes it. The same applies to an
unpartitioned store's dump.

`column-meta-csv` writes one `columnMeta.csv` with one row per concept. For a partitioned store
that row merges the concept's metadata across partitions as the Java service's
`SummaryColumnMeta` does: summed observation and patient counts (a patient in two partitions
counts twice), unioned category values, widest width, min of mins and max of maxes, with the
block's offset and length. `--merged` is accepted for a partitioned store and refused for an
unpartitioned one. No per-partition `columnMeta.csv` is written: nothing on the harmonization
branch, in Java HPDS or the query service, reads one; the dictionary ETL reads the one file it
is given.

### Converting a store of format 1

`hpds-etl convert --store <old dir> --out <new dir>` reads a store written by Rust HPDS v1 to v3
(format 1) and writes one of the current format. An unpartitioned store converts to an
unpartitioned store; a partitioned one, one store per partition directory, to one partitioned
store whose blocks hold every partition's rows, merged as a partitioned load merges them. The
old combined store, if any, is never read: the partitions are the source. The key is
`<old dir>/encryption_key` unless `--key-file` names another, and a copy is installed in
`<new dir>` when it has none.

Every block of every partition is read once to learn which patients have rows in it, the check
`migrate` makes: a partition whose rows hold a patient its patient set lacks loses
`patients-indexed`, and that patient goes into the overlap set. The `patients-indexed` tags of
the old `.generation` are not trusted. A source whose `.generation` does not list exactly its
partitions' store ids (a publish under way or interrupted) is refused, and so are a source
already in the current format and a `--out` that is, lies inside or contains the source. The
source is never changed: it is read under a shared `flock` on its directory, the lock the v3
ETL held while publishing, so no load publishes into it during the read. The new store is
published as a load publishes one, under the store lock.
