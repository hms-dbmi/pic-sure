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
| `hpds-gen` | bin | Deterministic synthetic dataset generator: phenotype CSVs and, with `genomic`, VCFs |
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

## Generating genomic test data

`hpds-gen genomic --preset <edge|gdev|gbench> --out-dir <dir> [--seed N] [--threads N]` writes
`vcf/<group>.<chromosome>.vcf.gz` (BGZF; the edge preset adds plain `.vcf` files), one
`index/<group>.vcfIndex.tsv` in the format the Java `SplitChromosomeVcfLoader` reads (paths are
`vcf/<file>`; rewrite the directory for the container), one headerless
`pheno/<group>-allConcepts.csv` per group (the harmonization loader's consent partition format),
and `groups.tsv`. The same seed gives the same bytes. Patients are disjoint across groups and each
group's VCFs list only the sites present in that group. Each phenotype CSV also holds a
`\_topmed_consents\` row, valued with the group name, for every patient that has genotypes.

| Preset | Samples | Groups | Sites | VCFs |
|---|---|---|---|---|
| `edge` | about 40 | 2 | about 2,200, hand-placed rules plus 2,100 numeric values | 7 files, 100 KB |
| `gdev` | 10,000 | 4 | 2 million | about 1.14 GB gzipped |
| `gbench` | 50,000 | 10 | 8 million | about 17 GB gzipped (estimate) |

`--estimate` writes nothing and prints a size estimate from a sample of rows. Frequency,
annotation, gap and gene-size distributions are fitted to the 1000 Genomes files by
`crates/hpds-gen/tools/fit_profile.py`, which writes `src/genomic/profile_data.rs`.

## Generating BDC consent data

`hpds-gen generate --preset <tiny|dev|bench|bdcx|bdcdev> --out <file> [--profile <plain|bdc|bdc-edge>]
[--order <patient|concept>] [--seed N]` writes `allConcepts.csv`. The `bdc` profile adds the
consent concepts a BioData Catalyst store carries on top of `\_consents\`:

| Concept | Holders | Value |
|---|---|---|
| `\_topmed_consents\` | patients of TOPMed groups (one group per study, zero-based study plus group even) | their own study consent; for 40% of them (the twins) the TOPMed accession `phs0005NN.cN`, which they also hold as a second `\_consents\` value |
| `\_harmonized_consent\` | 35% of all patients | their own consent, or for a tenth the other group of their study |
| `\DCC Harmonized data set\...` | the same 35%, eight concepts at 55 to 95% coverage | labels or numbers |
| `\_parent_consents\` | patients of child studies (accessions `phs000004`, `phs000008`, `phs000012`), and twins outside child studies | the parent study's consent of the same group; for a twin its own consent |

The profile only adds rows: the plain rows are byte for byte those of the plain profile for the
same shape and seed, except that the twins gain a second `\_consents\` value. The 10% sibling
group values of `\_harmonized_consent\` are a deliberate adversarial case: that concept then
differs from `\_consents\` for patients who hold both. `bdc-edge` also writes eleven hand-placed patients after the generated ones
(a patient with two consents, a consent nobody else holds, a topmed consent with no consent, a
topmed value from another study, a harmonized consent with no harmonized rows and the reverse,
a mismatched parent consent, a case variant, a patient with every consent, a patient with
none, and a patient whose `\_consents\` is the bare accession `phs000001`, which PSAMA holds for
a study with no consent group and which prefixes real values), so each edge of the authorization filter atoms has data to hit.

| Preset | Patients | Studies | Profile |
|---|---|---|---|
| `bdcx` | 3,000 | 8 | `bdc-edge` |
| `bdcdev` | 100,000 | 12 | `bdc` |

The row order defaults to `concept` under a bdc profile, which the Java `CSVLoaderNewSearch`
needs because it keeps only the last contiguous block of a concept; pass `--order patient` to
override.

## Images

Build from this directory:

```bash
docker build -f docker/hpds-rs.Dockerfile -t hms-dbmi/pic-sure-hpds-rs:LATEST .
docker build -f docker/hpds-rs-etl.Dockerfile -t hms-dbmi/pic-sure-hpds-rs-etl:LATEST .
```

### Building without crates.io access

A build host that cannot reach crates.io builds from a vendored copy of the dependencies. On a
machine that can, vendor them next to the workspace and copy the tree, `vendor/` included, to the
build host:

```bash
cargo vendor --locked vendor
```

Pass `--build-arg VENDORED=1` to either Dockerfile. The build then replaces the crates.io source
with `vendor/` and runs offline, so a missing crate fails the build instead of reaching the
network:

```bash
docker build --build-arg VENDORED=1 -f docker/hpds-rs.Dockerfile -t hms-dbmi/pic-sure-hpds-rs:LATEST .
docker build --build-arg VENDORED=1 -f docker/hpds-rs-etl.Dockerfile -t hms-dbmi/pic-sure-hpds-rs-etl:LATEST .
```

The runtime stage of the ETL image still installs `ca-certificates` from the Debian package mirror,
so that build needs the mirror even when it needs no crates.io.

A local `vendor/` directory adds about 342 MB to the default build context, so move it aside
when building without `VENDORED=1`.

`vendor/` is not committed. Regenerate it whenever `Cargo.lock` changes. The build still uses
`--locked`, so a stale `vendor/` fails on the missing package instead of building old code.

### Continuous integration

`.github/workflows/rust-hpds.yml` runs when `services/pic-sure-hpds-rs/**` changes. It checks
formatting, runs clippy with warnings
denied, runs the workspace tests and the library tests in release mode (one test only compiles
in a release build), audits `Cargo.lock` with `cargo audit`, checks licenses, bans and sources
with `cargo deny` against `deny.toml`, uploads a CycloneDX SBOM for each binary as the
`hpds-rs-sbom` artifact, builds both images for `linux/amd64`, and builds both build stages
offline from a freshly vendored `vendor/` so the offline build cannot regress. A weekly run only
audits and checks the supply chain, so a new advisory shows up without a code change.

`hpds-rs` allocates with jemalloc, which fixes its page size when it is compiled. A binary
built for 4 KiB pages fails to start on a kernel with larger ones, which some arm64
distributions use (16 KiB on Asahi Linux; 64 KiB on RHEL 8 for arm64 and on RHEL 9's
`kernel-64k`, while RHEL 9's default arm64 kernel uses 4 KiB).
The Dockerfile therefore sets `JEMALLOC_SYS_WITH_LG_PAGE=16` (64 KiB pages, which run on all
three) when it builds for arm64. Do the same for a native arm64 build meant for other hosts:

```bash
JEMALLOC_SYS_WITH_LG_PAGE=16 cargo build --release -p hpds-rs
```

`hpds-rs` reads these environment variables:

| Variable | Default | Meaning |
|---|---|---|
| `HPDS_DATA_DIRECTORY` | `/opt/local/hpds/` | Data directory: one store, partitioned by consent inside its blocks or unpartitioned (see [Partition layout](#partition-layout)). Matches the Java service; the all-in-one stack bind-mounts the hpds-rs host directory here. |
| `SERVER_PORT` | `8080` | Listen port on `0.0.0.0`. |
| `HPDS_CACHE_BYTES` | 4 GiB | Byte bound of the decoded block cache. One cache entry holds a concept's block and, once a narrow selection has read the concept through it, its partition view (see [Narrow selections](#narrow-selections)), weighed together, so a read of one concept costs one lookup whatever the selection. A read of many concepts (a study-level `ANY_RECORD_OF`) sends one concept in 64 through the cache on each query, in rotation, and takes the others from a table of the entries it last found there. The table holds entries weakly and forgets every entry the cache drops, so the bound holds, and the cache still sees which concepts are read, though it sees one such read in 64: under eviction pressure a concept read only that way ages faster than one read on its own. The old name `CACHE_BYTES` is still read, with a warning. |
| `CACHE_SIZE` | ignored | Java's cube count; logged as ignored. |
| `HPDS_ALLOW_PLAIN` | `false` | Open a store with no key file. Local benchmarking only. |
| `HPDS_RESULT_CACHE_BYTES` | `0` (off) | Byte bound of the sync result cache, which holds COUNT, CROSS_COUNT, CATEGORICAL_CROSS_COUNT, CONTINUOUS_CROSS_COUNT and OBSERVATION_CROSS_COUNT answers (see [Result cache](#result-cache)). `0` turns it off. A bound above 0 but below 2,992 bytes can store no answer, since one answer may take at most a sixteenth of it; startup logs a warning saying so. |
| `HPDS_COMBINED_STORE`, `HPDS_PER_PARTITION_COUNTS` | ignored | Settings of the format 1 store layout; logged as ignored. |
| `HPDS_CONSENT_CONTRACT` | none, required | `main` or `harmonization`: which consent contract requests are read and scoped by (see [Consent contracts](#consent-contracts)). The service refuses to start without it, naming both values, because either default would scope some deployment's queries wrongly. The startup log, the `hpds_consent_contract` metric and, under `main`, the health body carry it. |
| `HPDS_REQUIRE_AUTHORIZATION_FILTER` | `true` | Rust-only name for Java's `hpds.requireAuthorizationFilter`, which is also accepted as a variable of that exact name (the Rust name wins when both are set). Under `HPDS_CONSENT_CONTRACT=main` it requires authorization filters, as in Java main (see [Consent contracts](#consent-contracts)). Under `harmonization`, despite the name, it gates `userConsents`, not authorization filters: when true, every query on `/query/sync` and `POST /query` needs at least one non-null `userConsents` value and is otherwise a 400 "User consents are required for this query", and an unpartitioned data directory is refused at startup. When false, a query without consent values reads every partition. `authorizationFilters` are validated on `POST /query` but never applied, in either mode, as on the Java branch. An open (non-auth) backend must run with it false, since the query service sends that backend no consents. |
| `ID_BATCH_SIZE` | `0` | Java's export switch. `0` ends every DATAFRAME export in ERROR with "exports are disabled (ID_BATCH_SIZE=0)"; any larger value enables exports (the batch size itself does not change the algorithm). PATIENTS is unaffected. |
| `HPDS_EXPORT_PIN_BYTES` | 2 GiB | Cap on the decoded blocks one DATAFRAME export holds in memory, summed over the blocks it pins. Each concept's whole block counts, every partition's rows, whatever the selection, since that is what the export holds. A wider export ends in ERROR with "export too wide; select fewer concepts". |
| `HPDS_PREFIX_READ_BYTES` | 512 MiB | Bound on the decoded blocks one `ANY_RECORD_OF` holds in memory outside the block cache, by the decoded size bound the index records for each concept. A prefix covering more is read in chunks within the bound, each merged and dropped before the next loads; a single concept larger than the bound is read alone. What all reads together hold is bounded by `HPDS_PREFIX_READ_BUDGET_BYTES`. |
| `HPDS_PREFIX_READ_BUDGET_BYTES` | 1 GiB | Bound on the decoded blocks every `ANY_RECORD_OF` in the process holds at once outside the block cache, summed over queries and filters. Each chunk takes its share, for the concepts the cache does not hold, before it loads and gives it back once merged; a chunk that does not fit waits until enough is released, and a chunk larger than the whole budget waits for it to empty and runs alone. A concept the chunk found in the cache is charged as well from the moment the cache drops it while the chunk still holds it until the chunk lets it go, so no chunk holds a block that neither the cache nor the budget counts. The held bytes can then exceed the budget, by at most one chunk for each thread reading one, and later chunks wait until they fall back. One kind of memory stays outside both counts: the cache frees what it evicts, and the copy a load shares with concurrent loaders, through deferred reclamation (`crossbeam-epoch`), which keeps them alive until the thread that unlinked them flushes and the global epoch has advanced twice. After each chunk the read has every query pool thread flush when it is next free and collects four times itself, so once a read returns what may remain is at most 63 unlinked blocks for each pool thread that has not flushed yet, plus those unlinked in the last two epochs, until the next collection anywhere in the process. The `ANY_RECORD_OF` clauses of one subquery run on threads of their own, at most as many in the process as the query pool has threads, so a clause waiting here never blocks a pool thread; each clause's patients are merged into the subquery's set as soon as it finishes, so a waiting clause keeps no other clause's result. Waits and held bytes are in `/metrics`. |
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
| `HPDS_LOG_FORMAT` | `default` | `logback` writes every line, on stdout and in the log file, in Java HPDS's `log.pattern` layout (see [Logging](#logging)). `default` keeps the `tracing` line. Anything else fails startup, naming both values. |
| `HPDS_LOG_NAME` | `hpds` | The service tag in the first bracketed field of the `logback` format (Java uses `hpds`, `auth-hpds` and `open-hpds`). Ignored by the default format. Control characters and `]` are refused at startup. |
| `HPDS_SEARCH_GZIP` | `off` | `on` compresses `POST /search` answers for requests whose `Accept-Encoding` accepts gzip (see [Search compression](#search-compression)). Off, every answer has the bytes and headers it had before compression existed, whatever the request accepts. Read like `HPDS_METRICS`. |
| `HPDS_METRICS` | `off` | `on` serves `GET /metrics` in the Prometheus text format and counts requests (see [Metrics](#metrics)). Off, `/metrics` is a 404 like any unknown path. `true`, `false`, `1`, `0`, `yes` and `no` are also read; anything else fails startup. |
| `HPDS_EXCERPT_CONCURRENCY` | half `HPDS_ENGINE_CONCURRENCY`, at least 1 | VCF_EXCERPT, AGGREGATE_VCF_EXCERPT and VARIANT_LIST_FOR_QUERY requests computed or sent at once. An excerpt holds its excerpt slot and an engine slot until its last row is sent; a variant list holds an engine slot while it is built and its excerpt slot until it is sent. The cap must be below `HPDS_ENGINE_CONCURRENCY`, and 1 when that is 1 (startup fails otherwise; an engine concurrency of 1 logs a warning), so excerpts can never hold every engine slot and other queries keep running while they stream. A request over the cap waits up to `HPDS_EXCERPT_QUEUE_SECONDS` for a slot and is otherwise a 503 with `Retry-After`, counted in `hpds_excerpts_refused_total`; callers going through the query service see a 502 instead, without the header, since it answers 502 for any HPDS 5xx. A type whose setting is off answers its text without a slot. |
| `HPDS_EXCERPT_QUEUE_SECONDS` | `5` | How long an excerpt or variant list over `HPDS_EXCERPT_CONCURRENCY` waits for a slot before it is a 503 (`Retry-After` of this many seconds, at least 1). `0` refuses at once. |
| `HPDS_EXCERPT_STALL_SECONDS` | `30` | How long a streamed excerpt or variant list waits for its client to take the next 64 KiB chunk. A client that stops reading for longer has its stream ended (the response stops short of its `Content-Length`) and its slots released. |
| `HPDS_WRITE_STALL_SECONDS` | `60` | How long a write to any connection may stay pending, its client making no room in its socket buffer, before the connection is closed and counted in `hpds_connections_write_stalled_total`. It frees the socket and everything the response holds, which the excerpt stall bound alone cannot do once the client's buffers are full. |
| `HPDS_EXCERPT_STREAM_SECONDS` | `600` | How long a streamed excerpt's or variant list's whole body may take once its headers are sent; past it the stream is ended the same way. `HPDS_SYNC_TIMEOUT_SECONDS` bounds the wait for the engine slot and the measuring pass before the headers. |
| `hpds.genomicProcessor.impl` | unset | Java's genomic layout setting, also read as `HPDS_GENOMICPROCESSOR_IMPL` (Spring's relaxed name; the dotted name wins when both are set). `local`, `localDistributed`, `localPatientDistributed` or `localPatientOnlyDistributed`, compared ignoring case as Spring's `havingValue` compares, opens the genomic store in `HPDS_GENOMIC_DATA_DIRECTORY` and requires it to have been built in that layout; unset or blank serves no genomic data, as Java's no-op processor does. `remote` and any other value fail startup: there is no separate genomic service (see [Genomic store](#genomic-store)). |
| `HPDS_GENOMIC_DATA_DIRECTORY` | `/opt/local/hpds/all/` | Directory of the Rust genomic store (`geno.index`, `geno.blocks`, `.generation`). Java's default. Read only when `hpds.genomicProcessor.impl` is set. |
| `HPDS_GENOMIC_CACHE_BYTES` | 2 GiB | Byte bound of the decoded genomic block cache, separate from `HPDS_CACHE_BYTES` so a genomic scan cannot evict phenotype blocks. Every genomic block kind shares it. A query planning to read more than a quarter of it scans instead (next row). |
| `HPDS_GENOMIC_READ_BUDGET_BYTES` | 1 GiB | Bound on the bytes every genomic scan of the process holds at once outside the cache, each block counted at its stored plus decoded length. A block that does not fit waits. |
| `VCF_EXCERPT_ENABLED` | `false` | Allows VCF_EXCERPT, and with it AGGREGATE_VCF_EXCERPT and VARIANT_LIST_FOR_QUERY, as in Java. Off, those answer Java's `<TYPE> query type not allowed` with 200, checked before the genomic filters and the genomic store's state, as Java checks it first. Read with Spring's boolean words, ignoring case: `true`, `on`, `yes`, `1` or `false`, `off`, `no`, `0`; any other value, empty included, fails startup. |
| `AGGREGATE_VCF_EXCERPT_ENABLED` | `false` | Allows AGGREGATE_VCF_EXCERPT and VARIANT_LIST_FOR_QUERY. Read like `VCF_EXCERPT_ENABLED`. |
| `ID_CUBE_NAME` | `NONE` | Concept whose value heads each patient column of VCF_EXCERPT, found as Java's `getValueForKey` finds it; `NONE` heads the columns with the patient id. A numeric concept some subset patient holds is a 400, where Java fails. |
| `variant.info_column_order` | empty | Comma-separated INFO columns of the excerpts; when it names any, the excerpts hold exactly the listed columns that exist, in that order. Also read as `VARIANT_INFO_COLUMN_ORDER`. Empty keeps Java's `HashSet` order of every column. |
| `HPDS_MAX_EXCERPT_BYTES` | 1 GiB | Largest VCF_EXCERPT, AGGREGATE_VCF_EXCERPT or VARIANT_LIST_FOR_QUERY body answered; a larger one is a 400 naming the setting, measured before anything is sent. Java builds excerpts in memory without a bound. |
| `_RJEM_MALLOC_CONF` | unset | Options for the service's allocator, jemalloc, in its `MALLOC_CONF` syntax. The service sets `background_thread:true,dirty_decay_ms:1000,muzzy_decay_ms:1000`, so memory a broad query frees goes back to the system within about a second; this variable is applied after those and overrides them. glibc's `MALLOC_ARENA_MAX` and `MALLOC_TRIM_THRESHOLD_` have no effect. Idle, the service holds about 120 MiB under a kernel whose transparent huge pages are set to `always` (the default of Docker Desktop's VM), against about 55 MiB with glibc's allocator: jemalloc maps memory in 2 MiB-aligned extents, and the kernel backs each one it touches with a whole huge page, so about 110 MiB of the 120 is huge pages over some 55 MiB of live data. Fewer arenas (`narenas:1` to `narenas:8`), faster decay and jemalloc's own `thp:never` do not change it. Turning huge pages off for the whole process with `prctl(PR_SET_THP_DISABLE)` brings idle to about 55 MiB but costs 3 to 6% of throughput on single-request loads, so the service leaves them on. With `transparent_hugepage` set to `madvise` or `never` on the host the difference goes away. |

A query over a limit is a 400 naming the setting, before any block is read. Checks run in
this order on both query routes: the query's shape (select paths, clause depth and nodes), then
its consents, then the concepts it touches in the partitions those consents select, then (on
`POST /query` only) `QueryValidator`.

The key is read from `<HPDS_DATA_DIRECTORY>/encryption_key`. When the data directory cannot be
opened (no store, a store of format 1 written by Rust HPDS v1 to v3, a partition left in
javabin form, a `.generation` mismatch, an interrupted publish awaiting `hpds-etl recover`,
a block that disagrees with the index, an
unpartitioned store while consents are required) the server still starts, logs the open
error, health reports DOWN with a reason, and query routes answer 503. The startup log line
`store opened` carries `overlap_patients`, the size of the overlap set (see
[Partition layout](#partition-layout)). At shutdown the service logs what its block cache
holds: blocks, partition views, and the bytes of each.

### Consent contracts

`HPDS_CONSENT_CONTRACT` names the contract of the query service in front of this service.

- **`main`**: Java HPDS main as BDC runs it (tag `v4.1.1`). The query service replaces every
  auth query's `authorizationFilters` with the caller's consents (`\_consents\`, plus
  `\_topmed_consents\` with genomic filters and `\_harmonized_consent\` with a harmonized
  phenotypic filter), and HPDS applies them. Each filter is a `FILTER` on its path and values,
  and the filters are ANDed with each other and with the phenotypic clause into one authorized
  cohort that every result type starts from: COUNT, the cross counts, PATIENTS, DATAFRAME and
  the genomic types (whose filters narrow it further, so `\_topmed_consents\` scopes them). The
  store may be unpartitioned (a migrated BDC javabin) or partitioned, read as the union of its
  partitions; no consent value is mapped to a partition. Every cohort is checked against the
  authorized set before it is used.
- **`harmonization`**: the consent-partitioned branch. `userConsents` select partitions, and
  `authorizationFilters` are validated on `POST /query` and never applied. The rest of this
  README describes it where it says consents.

What a caller sees under `main`, against Java `v4.1.1`:

- The request model is main's seven fields: `select`, `authorizationFilters`,
  `phenotypicClause`, `genomicFilters`, `expectedResultType`, `picsureId`, `id`. Any other
  field, `userConsents` included, anywhere in the query, is a 400 naming it (``unknown field
  `userConsents` ``); Java answers 500. A clause mixing filter and subquery fields names the
  subquery field the same way. Numbers, booleans and enum ordinals are read as Jackson reads
  them, as under `harmonization`.
- `authorizationFilters` absent, `null` or `[]` is the empty list. Each filter's `values` is a
  set: repeats and order change nothing. A filter on an unknown concept, with an unknown value
  or with `values: []` selects nobody; values on a numeric concept are a 400 "... is not a
  categorical variable", where Java fails with an empty 200.
- A key `authorizationFilters` repeated in the query keeps its last value, as Jackson does.
  Every other repeated key is a 400, as under `harmonization`.
- `/query/sync` runs no validator, as Java does, so an unknown authorization concept counts 0.
  With `HPDS_REQUIRE_AUTHORIZATION_FILTER` true, a sync query whose `authorizationFilters` is
  missing, `null` or empty is a 400 "Authorization filter is required for this query:
  authorizationFilters is missing or empty and hpds.requireAuthorizationFilter is on", checked
  before anything else. Java answers it over every patient. BDC's query service always sends
  `\_consents\`, so no BDC answer changes; behind the query service's `serve` mode the 400
  falls back to Java. INFO_COLUMN_LISTING is exempt: it reads no patient data. With the flag
  false the query answers over every patient, as in Java.
- `POST /query` runs Java main's validator in its order: the requirement, every phenotypic
  filter, then every authorization filter (values non-empty, a known concept, a categorical
  one). A refusal registers the job already in ERROR with Java's message, as Java does; Java
  shows only the status. A `null` filter or a `null` `conceptPath`, where Java's validator
  throws something else and creates no job, is a 400.
- A `null` filter, a `null` or missing `conceptPath` or `values`, and a `null` value looked up
  in a categorical concept are a 400 starting "an authorization filter" on `/query/sync`, where
  Java answers 200 with an empty body (500 with audit logging on).
- Category and continuous cross counts list only the phenotypic clause's concepts; CROSS_COUNT
  and OBSERVATION_CROSS_COUNT list `select`, so an authorization concept appears there only
  when selected, counted over the authorized set.
- `/search` answers Java main's `ColumnMeta`: `name`, `categorical`, then `categoryValues` for
  a categorical concept or `min` and `max` for a numeric one, then `observationCount` and
  `patientCount` (distinct patients over the whole store). No storage field and no timestamp,
  as Java writes none. A null term answers the array of one-entry objects, as under
  `harmonization`; the INFO block and `/search/values/` are the same under both contracts.
- `GET /actuator/health` adds `"consentContract":"main"`.
- The sync `queryMetadata` header stays a random UUID, where Java derives a UUIDv5 from the
  query.
- AGGREGATE_VCF_EXCERPT's "NOT in subset" column counts carriers outside the authorized cohort,
  as Java's does.

### Genomic store

The genomic store is its own directory, `HPDS_GENOMIC_DATA_DIRECTORY`, built by
`hpds-etl genomic-migrate` (from a Java genomic store exported with `ExportGenomicForMigration`,
the production path) or `hpds-etl genomic-load` (from VCF index files and VCFs). It holds
`geno.index`, `geno.blocks` and `.generation`, and keeps Java's node structure: the layout
(`local`, `localDistributed`, `localPatientDistributed`, `localPatientOnlyDistributed`) is
recorded in `geno.index` and must equal `hpds.genomicProcessor.impl`. The service evaluates
each node as Java's `GenomicProcessorNodeImpl` does and combines nodes as that layout's parent
processors do, in the same process: there is no `remote` mode and no separate genomic service.
The directory is never written by the service (Java writes index caches into its node
directories at startup) and can be mounted read-only.

- **Key.** The store is sealed with the phenotype store's key,
  `<HPDS_DATA_DIRECTORY>/encryption_key`. Rotating the phenotype key therefore requires
  resealing the genomic store in the same change (rerun `genomic-migrate` or `genomic-load`
  with the new key); otherwise the service refuses the genomic store with an authentication
  error and reports DOWN. A separate genomic key is reserved in the format for a later
  version.
- **Startup.** The store opens after the phenotype store, read under a shared lock on
  `geno.index`. A directory holding Java genomic javabins and no `geno.index` is refused,
  naming `hpds-etl genomic-migrate`; so are a missing store, an interrupted publish, a
  `.generation` naming another store, a store sealed with another key and a store of another
  layout. In every case the service still starts, logs why, reports DOWN on
  `/actuator/health` with a genomic reason, answers every request that needs genomic data with
  503, and goes on answering phenotype-only requests. Java fails to start instead. The
  startup line `genomic store opened` logs the layout, groups, nodes, patients, variants, INFO
  columns, the cache and budget, and the largest chunk set.
- **Changes on disk.** As for the phenotype store, a genomic file changed in place, a publish
  journal in the directory, or `geno.index` replaced within the same store refuses every
  genomic request with 503 and reports DOWN until a restart; phenotype requests go on. A
  `geno.index` of another store moved in (a directory swap) leaves the open store serving and
  adds `"genomicStoreSuperseded":true` to health and `hpds_genomic_store_superseded 1` to
  `/metrics`; a restart serves the new store.
- **Memory.** On top of the phenotype bound, the process holds at most
  `HPDS_GENOMIC_CACHE_BYTES` plus `HPDS_GENOMIC_READ_BUDGET_BYTES`, plus per genomic thread
  (one per core) the largest chunk set and the info blocks a query reads, plus per-query
  state: an excerpt streams row by row, holding a chunk per node of the contig it walks, never
  the body. The genomic index is in memory (0.2 MB for the 1000 Genomes store), and the
  value lists of categorical INFO columns are kept once listed for search (about 0.3 MB for
  genes on 1000 Genomes). Size the container for the phenotype settings plus both genomic
  bounds.
- **Health.** With a genomic store served, the service is UP when the partitions hold a
  concept or the genomic store holds an INFO column, Java's readiness rule.

Java behaviors reproduced on purpose, because users compare genomic counts across systems
(each is a Java defect to fix in both services together later): numeric INFO ranges
intersect one variant set per distinct stored value; the last value of each 1,000-value
`CompressedIndex` partition, the column maximum included, never matches; a spec filter
matches every stored spec of its 1,000-bp bucket that starts with the request text, so a
four-field spec matches every gene and consequence annotation at the site; `0/0` and any
zygosity other than `0/1`, `1/1` and `""` select nothing; heterozygous no-calls count in
variant lists and not in patient filters; in the distributed layouts a filter on one node AND
a filter on another answers empty; the 100,000-variant threshold of the variant list is per
node and returns the node's unfiltered INFO seed above it; the excerpt counts carry Java's
padding errors (4 too high for a mask Java holds as a bitmask, a column 2 denominator of 4);
INFO_COLUMN_LISTING repeats a column per node or per group with `min` `3.4028235E38` and
`max` `1.4E-45`. A `min` or `max` bound is read as Jackson reads a `Float`: in a query sent as
a JSON object it is `Float.parseFloat(Double.toString(Double.parseDouble(text)))`, in a query
sent as a JSON string `Float.parseFloat(text)` directly, and the two differ on some inputs.

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
- A directory holding `.publish-journal` is refused until `hpds-etl recover` finishes the
  interrupted publish it records (see [Removing concepts](#removing-concepts)). The service
  never writes the data directory.
- The service holds a shared `flock` on the `pheno.index` it opened for as long as it runs, so
  an `hpds-etl` command that would replace the store files under it refuses to run, and a
  service starting while one publishes waits for it to finish.
- After startup the service reads the store only through what it opened: the index it holds
  in memory and its open `pheno.blocks` descriptor, read by offset. It never reopens a store
  path (the key, `.generation` and the directory listing are read once, at open). At most once
  a second it checks what it serves. Every query, `/search` and result download then answers
  503 asking for a restart, and health reports DOWN with that reason until the service
  restarts, when:
  - the `pheno.index` or `pheno.blocks` it holds open changed in place: a file is identified by
    device, inode, length and modification time, so `cp` over a file or `rsync --inplace` is
    seen, and so is a `touch`, which fails closed. A rewrite that keeps both the length and
    the modification time is not seen;
  - a publish journal appeared in the directory;
  - `pheno.index` became another file of the same store id, or one whose header cannot be
    read: an in-place `remove-concepts` or `recover` the lock did not reach (another kernel,
    a network filesystem), which withdraws data. An index of another format version is one
    whose header this format does not read, so a format-version swap refuses at once;
  - `pheno.index` became a symbolic link, a FIFO or anything but a regular file. The check
    opens it with `O_NOFOLLOW | O_NONBLOCK` and judges it by `fstat` on that handle, so it
    never follows a link or waits, and the startup open refuses such a `pheno.index` the same
    way.

  When `pheno.index` becomes a file of another store id, a whole store moved in (by
  `aio/load.sh` or `bin/migrate-javabins`, which `mv` the files while the old container still
  runs), the service logs it and goes on serving the store it opened from its open files, as it
  did before these checks, until it is stopped. That keeps the old container answering while
  the query service's DNS cache still points at it during a swap. Health stays UP and carries
  `"storeSuperseded": true`, and the `hpds_store_superseded` gauge is 1, so orchestration can
  see that a restart is due. A withdrawal done by reloading a store without the concept is
  therefore served from the old files until the old container stops (or is restarted on that
  signal); `remove-concepts` is the withdrawal that takes effect at once.
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
  answer: the phenotypic clause (with each filter's mark of a `null` among its values), and
  `select` for CROSS_COUNT and OBSERVATION_CROSS_COUNT (with its count of `null` elements). It
  also holds the deferred refusal a query carries for a clause Java fails on, the count of
  `null` elements of `authorizationFilters`, and `authorizationFilters` as their exact canonical JSON, filters and values in
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

### Logging

With `HPDS_LOG_FORMAT=logback` each line has the fields of Java HPDS's `log.pattern`, so Splunk
field extraction written for the Java service reads this one unchanged:

```
2026-10-21T14:03:07.412Z INFO  --- [auth-hpds] [tokio-runtime-worker] hpds_rs.jobs : store opened layout=Partitioned
```

The fields are a UTC timestamp with milliseconds, the level padded to five columns, `---`, the
service tag from `HPDS_LOG_NAME`, the thread name, the logger name (the module path with dots,
shortened from the left to 36 characters as `%logger{36}` does), `:` and the message. Fields an
event carries are appended to the message as `name=value`. A panic is logged as an error at the
logger `hpds_rs.panic`, and the default hook still prints it to standard error. The default format is unchanged.

#### Running under a host log directory owned by another UID

The image runs as UID 1000 and, with `HPDS_LOG_DIRECTORY` set, writes `hpds-rs.<date>.log` into
it. A bind-mounted host directory owned by another UID (root, or the account the Java service
used) cannot be written by UID 1000. Startup then fails, as Java's does when it cannot open its
log file: the service logs "cannot write the log file in <directory>" as an error on stdout and
exits non-zero. Either:

- run the container as the directory's owner: `docker run --user <uid>:<gid> ...`. The data
  directory must be readable by that user, and `HPDS_RESULTS_DIRECTORY` must be owned by it (see
  its row above), or
- give the directory to UID 1000 once: `chown 1000:1000 <host log directory>`.

A `hpds-rs.<date>.log` owned by the chosen UID in `ls -ln <host log directory>` means the file is
being written.

### Metrics

With `HPDS_METRICS=on`, `GET /metrics` reports these in the Prometheus text exposition format.
Labels take only fixed values: route templates as the router declares them (or `unmatched`),
result type names (or `none`), error kinds, histogram bounds, export outcomes and the consent
contract. A request
whose path matches a route but whose method does not (a 405) counts under that route's
template; only a path no route matches counts as `unmatched`. No label or value carries a
patient id, concept path, consent name, result id or anything from a request body. Counters
are relaxed atomic adds; the block cache's lookup counters are striped across cache lines.
Reading the block cache walks every cached concept to weigh the partition views, so a scrape
costs more as more concepts are cached (under 1 ms with 6,500 cached on the bench store) and
runs on the blocking pool.

| Metric | Type | Meaning |
|---|---|---|
| `hpds_http_requests_total{route,result_type}` | counter | Requests answered, by route template and, on `/query/sync` and `POST /query`, the result type the query named |
| `hpds_http_request_duration_seconds{route}` | histogram | Time from receiving a request to sending its response head; a streamed download's body is not timed. Bounds 0.5 ms to 60 s |
| `hpds_http_errors_total{kind}` | counter | 4xx and 5xx answers: `bad_request`, `not_found`, `request_timeout`, `payload_too_large`, `internal`, `unavailable`, `other_client`, `other_server` |
| `hpds_connections_refused_total` | counter | Connections refused at `HPDS_MAX_CONNECTIONS`, answered 503 when possible (closed at once when as many refusals are already being answered) |
| `hpds_connections_write_stalled_total` | counter | Connections closed because a write to them stayed pending for `HPDS_WRITE_STALL_SECONDS` |
| `hpds_block_cache_hits_total`, `hpds_block_cache_misses_total` | counter | Block cache lookups, one per concept read; a concept a study-level `ANY_RECORD_OF` takes from its table of recent entries (see `HPDS_CACHE_BYTES`) counts as a hit |
| `hpds_block_cache_evictions_total` | counter | Entries dropped (or never admitted) to stay within `HPDS_CACHE_BYTES` |
| `hpds_block_cache_entries`, `hpds_block_cache_bytes`, `hpds_block_cache_capacity_bytes` | gauge | Concepts and bytes held, blocks and views, and the bound |
| `hpds_block_cache_views`, `hpds_block_cache_view_bytes` | gauge | Partition views held and their bytes, part of the above |
| `hpds_prefix_read_budget_bytes`, `hpds_prefix_read_held_bytes`, `hpds_prefix_read_held_peak_bytes` | gauge | `HPDS_PREFIX_READ_BUDGET_BYTES`, the decoded bytes `ANY_RECORD_OF` chunks hold under it now, and the most held at once |
| `hpds_prefix_read_permits_total`, `hpds_prefix_read_waits_total`, `hpds_prefix_read_wait_seconds_total` | counter | Chunks granted bytes of the budget, chunks that waited for it, and the seconds they waited |
| `hpds_block_loads_total`, `hpds_block_load_failures_total`, `hpds_block_load_seconds_total` | counter | Blocks read from the store, authenticated and decoded into the cache; block reads that failed; and the seconds spent on both, summed over threads |
| `hpds_store_superseded` | gauge | 1 while a store of another id has been moved into the data directory and the service serves the store it opened from its open files (see [Partition layout](#partition-layout)); a restart serves the new one |
| `hpds_consent_contract{contract}` | gauge | 1, labelled `main` or `harmonization` from `HPDS_CONSENT_CONTRACT` (see [Consent contracts](#consent-contracts)) |
| `hpds_cohort_guard_refusals_total` | counter | Queries refused by the cohort check (see [Narrow selections](#narrow-selections)), and under the main contract by the check against the authorized set |
| `hpds_result_cache_hits_total`, `_misses_total`, `_evictions_total` | counter | Result cache lookups and evictions; only with `HPDS_RESULT_CACHE_BYTES` above 0 |
| `hpds_result_cache_entries`, `_bytes`, `_capacity_bytes` | gauge | What the result cache holds, and its bound; only when it is on |
| `hpds_async_results` | gauge | Async results held, pending, running and finished |
| `hpds_exports_finished_total{outcome}` | counter | Async queries finished: `memory`, `file` or `error` (refusals at submission included) |
| `hpds_result_memory_bytes`, `hpds_result_memory_budget_bytes` | gauge | Export results held in memory, downloads in progress included, and `HPDS_RESULT_MEMORY_BYTES` |
| `hpds_excerpts_ended_early_total{end}` | counter | Streamed excerpts and variant lists ended before their last byte: `stalled` (the client took no chunk within `HPDS_EXCERPT_STALL_SECONDS`), `expired` (not sent within `HPDS_EXCERPT_STREAM_SECONDS`), `aborted` (the client left, a read failed, or the body would have differed from its measured length), `store_changed` (the genomic store changed on disk between the measuring pass and the last chunk) |
| `hpds_excerpts_refused_total` | counter | Excerpts and variant lists answered 503 because no `HPDS_EXCERPT_CONCURRENCY` slot came free within `HPDS_EXCERPT_QUEUE_SECONDS` |
| `hpds_excerpts_in_progress`, `hpds_excerpt_concurrency` | gauge | Excerpt slots in use (an excerpt holds one while it is computed and streamed, a variant list while it is built and sent), and `HPDS_EXCERPT_CONCURRENCY` |
| `hpds_genomic_store_up` | gauge | 1 while the genomic store is served, 0 while one is configured and could not be served; absent without `hpds.genomicProcessor.impl` |
| `hpds_genomic_cache_hits_total`, `_misses_total`, `_loads_total`, `_evictions_total`, `_scan_reads_total` | counter | Genomic block lookups, blocks admitted, blocks dropped to stay within `HPDS_GENOMIC_CACHE_BYTES`, and blocks scans read under the budget without admitting them |
| `hpds_genomic_cache_entries`, `_bytes`, `_capacity_bytes` | gauge | Genomic blocks held, their bytes, and `HPDS_GENOMIC_CACHE_BYTES` |
| `hpds_genomic_read_budget_bytes`, `hpds_genomic_read_held_bytes`, `hpds_genomic_read_held_peak_bytes` | gauge | `HPDS_GENOMIC_READ_BUDGET_BYTES`, the bytes scans hold under it now, and the most held at once |
| `hpds_genomic_read_permits_total`, `_waits_total`, `_wait_seconds_total` | counter | Genomic blocks granted bytes of the budget, blocks that waited, and the seconds they waited |
| `hpds_genomic_largest_chunk_set_bytes`, `hpds_genomic_threads` | gauge | What one genomic thread may hold reading a block before it is cached, and the genomic pool's threads |
| `hpds_genomic_search_values`, `hpds_genomic_search_value_bytes` | gauge | INFO values kept in the categorical columns' search lists, and their bytes |
| `hpds_genomic_store_superseded` | gauge | 1 while another genomic store has been moved into the genomic directory and the service serves the one it opened |

### Routes

Mounted under `/PIC-SURE/v3` and under the unversioned `/PIC-SURE`. The Java branch serves only
`/PIC-SURE/v3`, but its query service still posts search to `/PIC-SURE/search`, which the Java
service answers 404, until the change that sends every HPDS call through `/v3` lands. Request bodies are capped at 2 MiB, a body that stalls for 30 seconds is cut
off, and a connection that takes more than 10 seconds to send a request's headers is closed.

| Route | Answer |
|---|---|
| `POST /query/sync` | COUNT (`text/plain`) and the four cross counts (`application/json`), byte for byte as Java, with a `queryMetadata` header, over the partitions `userConsents` selects and narrowed by `genomicFilters`; VARIANT_COUNT_FOR_QUERY and INFO_COLUMN_LISTING (`application/json`), VARIANT_LIST_FOR_QUERY, VCF_EXCERPT and AGGREGATE_VCF_EXCERPT (`text/plain`) from the genomic store. Excerpts carry their exact `Content-Length` and stream row by row. INFO_COLUMN_LISTING is `[]` without genomic data |
| `POST /query` | `QueryStatus` for a PATIENTS or DATAFRAME export over the partitions `userConsents` selects, narrowed by `genomicFilters`; DATAFRAME_TIMESERIES and DATAFRAME_PFB end in ERROR; genomic result types are a 400 |
| `POST /query/{id}/status` | `QueryStatus`, with Java's status mapping |
| `POST /query/{id}/result` | The CSV export as `text/plain`; 400 `Status : <state>` before it succeeds |
| `POST /query/{id}/signed-url` | 400, S3 upload is out of scope |
| `POST /query/format`, `POST /write/{dataType}` | 400, not supported |
| `POST /search` | The merged dictionary as `SummaryColumnMeta` (`name`, `widthInBytes`, `categorical`, `categoryValues`, `min`, `max`, `patientCount`, `timestampMin`, `timestampMax`, nulls written), not consent-scoped. A string term, `""` included, answers an object keyed by path, matched by case-insensitive path substring or exact category value; a null or missing term answers Java's array of one-entry objects for every concept. `info` holds, for a string term, each INFO column whose description or key contains it ignoring case, keyed in Java string order, as `{description, values, continuous}` with the values in Java's `HashSet` order (none for a continuous column); `{}` for a null term or without genomic data. See the locale note in `search.rs`. gzip on request, see [Search compression](#search-compression). |
| `GET /search/values/` | `{"results","page","total"}`: the values of INFO column `genomicConceptPath` containing `query` ignoring case, in Java's `HashSet` order, paged with Java's `int` arithmetic; an empty page without genomic data |
| `GET /actuator/health` | 200 `{"status":"UP"}` (with `"consentContract":"main"` under the main contract) when the store's dictionary holds a concept (or the genomic store an INFO column), with `"storeSuperseded":true` or `"genomicStoreSuperseded":true` added while a store of another id has been moved in (see [Partition layout](#partition-layout) and [Genomic store](#genomic-store)); else 503 `{"status":"DOWN","reason":...}` |
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
- When its store files are replaced while it runs, this service refuses every query with a 503
  until it restarts. Java keeps answering from the store it loaded at startup.
- Neither service binds an async result to the consents it was submitted with. The query
  service enforces read access (`verifyReadAccess`) before it hands out a result, as it does
  for Java.
- Java seeks the minimum index per partition (`seekForMinIndex`), so a range filter whose
  `min` is above every value of some selected partition answers an empty 200 there. This
  service answers normally.
- `VARIANT_COUNT_FOR_QUERY` with no `genomicFilters` is a 200 `{"count":"0","message":"No
  variant filters were supplied, so no query was run."}` with a `queryMetadata` header, as in
  Java. Java runs no consent check for it; this service applies its own, so with the flag on
  and no consent value it is the 400 "User consents are required for this query".
- Results are dropped oldest finished first when `HPDS_RESULT_LIMIT` or `HPDS_RESULT_MAX_BYTES`
  is reached, and after `HPDS_RESULT_MAX_AGE_SECONDS` when that is set; Java keeps them until
  restart.
- Without `hpds.genomicProcessor.impl`, `genomicFilters` are a 400 naming the unsupported
  feature, where Java's no-op processor fails with a `NullPointerException`; a variant-spec
  path in `select` or a phenotypic filter is an unknown concept, as in Java (an empty
  DATAFRAME column, a count of 0).
- Genomic result types submitted to `POST /query` are a 400 "result type <TYPE> is not
  supported by POST /query; send it to POST /query/sync". Java has no processor for them
  there and answers the submit 200 without a body; the query never runs.
- Where Java throws on a genomic request (answering 500 with audit logging on, or 200 with an
  empty body and an ERROR async status with it off, as at BDC), this service answers 400 with a
  message: a numeric INFO filter with one bound, a null bound or `min > max`; an `rs` key; a
  spec position that is not a number; a spec key of fewer than two fields; a `null` inside
  `values`; a numeric filter on a column some node holds as categorical; a selected key or
  column whose `column + "_____" + key` does not split back into the two; a listed spec of
  fewer than six fields an excerpt or an INFO set would parse on a node with patients in
  scope; duplicate metadata strings in an excerpt; in `local` and `localDistributed`, an
  excerpt spec no node holds; an `ID_CUBE_NAME` concept that is numeric for some subset
  patient; `/search` with a term matching a categorical INFO column some node lacks;
  `/search/values` for a column some node lacks, an unknown column included, or a page and
  size whose `int` products make Java's `subList` throw. Messages never name a node or a
  variant.
- Refused where Java answers: an invalid genomic filter is refused even when the phenotype
  cohort is empty, where Java skips the genomic step and answers 0; a `null` value is refused
  even where no spec matches or the column has no info store; a `"_____"` key whose split lands
  on an existing key is refused; a spec filter invalid as sent is refused at admission even on
  a variant type with no INFO filter, where Java never evaluates it and answers empty.
- Rust drops missing samples (`missing-samples-dropped`): `genomic-load
  --skip-missing-samples` leaves out of a group's patient list a patient absent from every VCF
  of its group, where Java's loader copies the last VCF sample's genotypes into it and reports
  it as a carrier or as homozygous reference. On the AIO 1000 Genomes data that is 2,430 of
  4,978 patients, so N, bitmask lengths and excerpt denominators follow the shorter list. On
  records with a longer FORMAT than `GT` (`GT:DP`), Java credits the last header column's
  genotype to the latest index sample mapped to it instead of the real last sample;
  `genomic-load` keeps each genotype with its real sample. A store built by `genomic-migrate`
  keeps Java's data as stored, defect included, and answers exactly as Java does.
- AGGREGATE_VCF_EXCERPT column 2, "Patients with this variant NOT in subset", counts carriers
  outside the query's subset, patients outside the caller's consents included, as Java does.
  It is a count, not identities, and BDC's default explorer view; the genotype cells of
  VCF_EXCERPT stay limited to the subset. Reproduced for equivalence; the consent question is
  open with the PIC-SURE team.
- VCF_EXCERPT, AGGREGATE_VCF_EXCERPT and VARIANT_LIST_FOR_QUERY bodies over
  `HPDS_MAX_EXCERPT_BYTES` are a 400. They stream with their exact `Content-Length`; a
  read failure after the headers are sent, a client that stops reading for
  `HPDS_EXCERPT_STALL_SECONDS`, a body still unsent after `HPDS_EXCERPT_STREAM_SECONDS`, or a
  genomic store changed on disk between the measuring pass and the last chunk ends the
  response before that length, which a client sees as a failed transfer, never as a complete
  body of other rows; a client that makes no room in its socket buffer has its connection closed after
  `HPDS_WRITE_STALL_SECONDS`. At most `HPDS_EXCERPT_CONCURRENCY` of them run at once; one past
  the cap waits up to `HPDS_EXCERPT_QUEUE_SECONDS` and is then a 503 with `Retry-After`, which
  the query service turns into a 502 for its caller. Java has no such cap. A measuring pass that outlives `HPDS_SYNC_TIMEOUT_SECONDS` answers 503; it
  is not interrupted, finishes, writes nothing and releases its slots.
- Order: VARIANT_LIST_FOR_QUERY lists specs in the store's spec order, where Java's is a hash
  or skip-list order; INFO_COLUMN_LISTING lists nodes or groups in index order and columns in
  column order; VCF_EXCERPT patient columns follow index group order, where Java's follows
  `File.listFiles`. Excerpt INFO columns and `/search` and `/search/values` value lists follow
  Java's `HashSet` order, except inside one hash bucket, where Java's own order is not
  specified. When two chromosome nodes of one group hold the same spec, its masks come from
  the first node in index order, where Java's node order is unspecified; which row an excerpt
  keeps when two rows collapse is unspecified in Java too.
- The genomic store is encrypted with the phenotype store's key; Java's genomic files are
  plain. The service never writes the genomic directory.
- A genomic store that is configured but cannot be served, or that changes on disk, makes
  health DOWN and genomic requests 503 while phenotype requests are answered (see
  [Genomic store](#genomic-store)), except VARIANT_LIST_FOR_QUERY and the excerpts with their
  setting off, which answer their `not allowed` text first. `POST /search` with a term is a
  503 while the genomic store could not be opened, since which INFO columns match cannot be
  known; after it changed on disk, a term matching no INFO column of the index held in memory
  answers the phenotype results with an empty `info`, and one matching a column is a 503.
  Java fails to start, or keeps answering from what it loaded.
- `authorizationFilters` never narrow a query, as on the Java consent branch, which only
  validates them; `userConsents` choose the partitions instead. The original Java HPDS (main)
  applies each authorization filter as one more AND clause, so its answers shrink to the
  patients those filters allow. This service must not replace a main-branch HPDS in a
  deployment that relies on `authorizationFilters` for access control: it would return every
  patient the query matches.

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
that writes a store takes (`load`, `migrate`, `convert`, `remove-concepts`, `recover`,
`column-meta-csv`), so two of them never interleave, also from separate containers sharing the
directory (on one kernel, see below). A publish into an existing `--out` is refused, before
anything is built, while a running query service holds that store open (see
[Partition layout](#partition-layout)), and while it holds an interrupted publish's journal. `--out` is refused when it holds a partitioned store of format 1 (partition
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

On NFS and EFS specifically, none of these locks should be relied on, and nothing here is
designed around them:

- The Linux NFS client emulates `flock` with POSIX byte-range locks, sent to the server
  (NLM for NFSv3, the protocol itself for NFSv4). Locks then do reach other clients, but only
  when every client mounts with locking enabled (not `nolock` or `local_lock=all`), and they
  behave as POSIX locks, below.
- An emulated exclusive lock needs a file opened for writing. The store lock is a `flock` on
  the store directory's own descriptor, which can only be opened read only, so on such a mount
  it can fail with `EBADF` and the command stops. The lock on `pheno.index` opens the file for
  writing for this reason, falling back to read only for a dry run on a read-only mount.
- POSIX locks belong to the process and the file, not the descriptor: closing any descriptor
  of a file drops every lock the process holds on it. The `hpds-etl` commands read
  `pheno.index` through another descriptor while they hold their lock on it, so on such a mount
  they drop their own lock early, and a service starting meanwhile is not held off.
- The service's stamp check still works where the server reports stable inode numbers and
  modification times, so a store replaced under a service on another client is refused within
  a second of the service seeing the change, subject to the client's attribute cache.

A crash while `load`, `migrate` or `convert` publishes into an existing directory is not
journaled as `remove-concepts` is: the files are renamed one by one, and the new store has a
new id. It fails closed. Until `pheno.index` is renamed, it and `pheno.blocks` name different
store ids, and the service and every `hpds-etl` command refuse the store. Between that rename
and the `.generation` marker the two files form the whole new store, and the service still
refuses it because the marker names the old id. Rerun the load to publish it whole.

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
`remove-concepts` keeps the store id, the partition patient sets and the overlap set: removing
rows never puts an overlap patient into an untagged block. It narrows each partition's tagged
set to the overlap patients that still have a row tagged with it (see
[Removing concepts](#removing-concepts)).

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

### Removing concepts

`hpds-etl remove-concepts --store <dir> --concepts <file>` withdraws concepts from a store, the
step `RemoveConceptFromMetadata` performs on a javabin store with `conceptsToRemove.txt`. The
file is UTF-8, one concept path per line, split as `BufferedReader.readLine` splits it. A line
removes the concept whose path equals it exactly once U+FFFD is removed from it (Java matches
the raw metastore keys, which still hold U+FFFD; this store holds them stripped). Lines are not
trimmed and never match a prefix. Empty lines are skipped. The key is checked as `load` checks
it: `--key-file` must equal the store's `encryption_key`, and `--plain` is refused over a store
with one.

Run it with the service stopped: stop it, run `remove-concepts`, start it. The command refuses
to run while a query service holds the store open, because a running service keeps serving the
index and blocks it read at startup, the removed concepts included, and would keep the old
blocks file alive on disk. Removal is undone by reloading the store from inputs that still hold
the concepts, so take them out of the inputs too.

- **Every line must name a concept.** A line that names none (a trailing space, missing
  backslashes, a typo) fails the command before anything is written, listing each such line as
  it is in the file, between double quotes, with its number and a hint: a concept matches it
  without the surrounding whitespace, or with a backslash at each end, or the maintenance log
  records it as removed by an earlier run. `--allow-missing` removes the concepts that exist and lists the rest,
  for a rerun after the concepts are gone. A file with no line naming anything always fails.
- **`--dry-run`** reads the store under the shared store lock and prints what a run would do:
  the removed paths, their observations, the distinct patients with a removed row, the overlap
  patients dropped from tagged sets, the size `pheno.blocks` would have, and `store_in_use`
  when a service holds the store open (the probe retries a few times, so two dry runs at once
  do not report each other). It writes nothing, works on a read-only mount, refuses symbolic
  links as a run does, and fails after the report when a run would fail on lines naming no
  concept.
- **Compaction is the default.** `pheno.blocks` is rewritten to hold only the remaining
  concepts' blocks, each verified on the way, so the removed observations are no longer in the
  store's files; the store keeps its id, so blocks are copied without being re-encrypted.
  `--keep-removed-blocks` rewrites only `pheno.index` and leaves the removed observations in
  `pheno.blocks`, encrypted but present, with a warning. Use it only when the data does not
  need to leave the disk. Compaction drops every byte no remaining concept references, so a
  later run without it compacts what it left, even when that run removes nothing (rerun the
  same list with `--allow-missing`); the report and the log count those bytes as
  `unreferenced_bytes`.
- **What compaction does not erase.** The old `pheno.blocks` is unlinked, not overwritten: its
  sectors stay on the disk until the filesystem reuses them, and a process that still has it
  open (a service that was not stopped) keeps it readable until it closes it. Backups,
  snapshots, copies of the store and copies of the inputs are outside the store. Erasing those,
  and the disk itself where that is required, is the operator's responsibility.
- **Crash safety.** The new files are staged beside the old ones and synced, then a journal,
  `.publish-journal`, records each staged file with its target, length and XXH3-128, and the
  record the run will log. Only then are `pheno.blocks` and `pheno.index` renamed into place,
  `columnMeta.csv` regenerated and the record appended, and the journal is removed last. A crash
  before the journal leaves the old store whole. After it, `hpds-etl recover --store <dir>`
  finishes the publish: it checks the journal's record against the index it publishes (the
  index must open under the key, hash to the record's `index_sha256`, carry its store id and
  remaining count, and hold none of its removed paths), renames each staged file still present
  after checking its length and hash, checks the files already renamed, regenerates
  `columnMeta.csv`, appends the record unless the log holds it, and removes the journal. A
  staged entry that is a symbolic link, not a regular file, or not the journaled file is
  refused, and so is a journal naming other files, the same file twice, or another order than
  `pheno.blocks` then `pheno.index`. When a staged file is gone and nothing was renamed yet,
  `recover` checks that the store's own files open whole, every block verified, and rolls the
  publish back instead (`rolled-back-by-recover`). When one file was renamed and another's
  staged copy is gone it can do neither, says so, and leaves the store as it is.
  `remove-concepts` runs the same recovery first. Until then the query
  service refuses to open the directory, and `dump`, `column-meta-csv`, `--dry-run` and a load
  into the directory refuse it, all naming `hpds-etl recover`. `recover` also removes staged
  files a killed run left before journaling. The store format is unchanged.
- **The maintenance log.** Every run that is not a dry run appends one JSON line to
  `maintenance-log.jsonl` in the store: `time` (UTC), `operation`, `result` (`published`,
  `no-change` for an `--allow-missing` run that found nothing to remove or compact,
  `completed-by-recover` or `rolled-back-by-recover`, whose record lists no removed paths
  and no removal counts, since nothing was withdrawn; `recover` also says so and that the
  removal must be run again),
  `tool`, `store_id`, `publish_id`, the `concepts_file` path and its `concepts_file_sha256`,
  `removed_paths`, the line, observation, patient and byte counts, `compacted`,
  `removed_blocks_kept`, and `index_sha256`, the SHA-256 of the `pheno.index` it left (the
  current one for `no-change`, none for a rollback), so
  `sha256sum pheno.index` tells whether the store is still the one the last record describes.
  No patient id or value is written. The log is created mode `0600` (an existing log with
  another mode is set to `0600`), never through a symbolic link, and each record is synced
  before the command succeeds. Records are a fixed shape: the one a journal carries is parsed
  with unknown fields refused before `recover` appends it. `.generation` and
  `etl-report.json` are left as they are: the store id does not change (every block's
  associated data binds it), and the report describes the load that built the store.
- **Files.** Every file it creates is mode `0600`. It refuses a store whose `pheno.index`,
  `pheno.blocks`, `columnMeta.csv`, `.generation`, journal or log is a symbolic link, since
  renaming over a link leaves the file it points to, and its data, in place. The concepts file
  must be a regular file of at most 64 MiB.
- **Partitions.** The partition patient sets and the overlap set are kept, as Java keeps
  `allIds`. Each partition's tagged set, which the service's cohort guard reads as the overlap
  patients a selection of that partition may answer for, is narrowed to the patients that still
  have a row tagged with it.

Differences from Java: a line naming no concept fails the command (Java ignores it) unless
`--allow-missing`; the removed observations leave `pheno.blocks` (Java leaves them in
`allObservationsStore.javabin`); `columnMeta.csv` is regenerated (Java leaves that to
`CreateColumnmetaCSV`); the replacement is crash safe and logged (Java rewrites
`columnMeta.javabin` in place); and a running service is refused (Java's service keeps serving
what it loaded).

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
