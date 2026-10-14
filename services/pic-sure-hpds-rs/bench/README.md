# bench

Harness for comparing the Java HPDS service with the Rust one, and for measuring either.
The service harness drives `hpds-bench` (`crates/hpds-bench`) over the `PIC-SURE/v3` HTTP
contract; the ETL parity harness compares the stores the two ETLs build.

| Path | Purpose |
|---|---|
| `java-fixture.sh` | Builds the 1000 Genomes phenotype store with the Java ETL and runs the Java service on it |
| `divergences.json` | Accepted differences between Java and Rust, applied by `compare --divergences` |
| `golden/1000genomes.json` | Expected answers for the fixture, checked by `compare --golden` |
| `baseline.sh`, `docker-compose.yml` | Java baseline stack and profiling matrix |
| `arms.sh` | Java as-is, Java on the new layout and `hpds-rs` (encrypted or plain), measured one at a time: ETL, cold and warm load, exports, memory at rest. With `BENCH_NETWORK` the load client runs in a container on that Docker network, so no measured request passes through Docker Desktop's port forwarding |
| `pair-parity.sh`, `async-compare.py`, `search-encoding.py` | Two `hpds-rs` builds on their own stores, answer for answer, export bytes and search encodings included, see [Pair parity](#pair-parity) |
| `etl-parity.sh`, `etl-parity-compare.py` | Java ETL vs `hpds-etl` on the same inputs, see [ETL parity](#etl-parity) |
| `etl-parity-partitioned.sh` | Java `CSVLoader` vs `hpds-etl load --mode partitioned`, partition by partition, see [Partitioned ETL parity](#partitioned-etl-parity) |
| `etl-divergences.json` | Accepted differences between the Java and Rust stores, each citing the spec |

## Java fixture

```bash
bench/java-fixture.sh all      # build the store, start the container, wait for health
bench/java-fixture.sh stop
```

The container is named `bench-fixture-hpds-java` and publishes `127.0.0.1:18081` only. It needs
the `hms-dbmi/pic-sure-hpds:bench` and `hms-dbmi/pic-sure-hpds-etl:bench` images; the script
prints the build commands when they are missing. `start` replaces a running container whose
image, data mount or published address differs from the current settings.

## Corpus

```bash
hpds-bench corpus --column-meta bench/data/1000genomes/columnMeta.csv --seed 1 --out /tmp/corpus
```

The corpus holds fixed entries, error-path entries, search entries and generated entries.

- Error-path entries (`error-*`, flagged `errorPath`) are invalid requests: a FILTER with neither
  values nor a range, values together with min or max, an empty subquery, an async-only type sent
  to `/query/sync`, unknown async ids, malformed query JSON, an unknown field, non-empty
  `genomicFilters`, and REQUIRED with values. `load` never replays them.
- Search entries (`search-*`) post a term to `/search`: a path substring, an exact category
  value, and, when the metadata has `\_studies_consents\`, terms drawn from it.
- Authorization entries filter on `\_consents\`; the multi-filter variant adds
  `\_topmed_consents\` and `\_harmonized_consent\` when the metadata has them, as the query
  service does. Without `\_consents\` they filter on the categorical concept with few categories
  that covers the most patients.
- ANY_RECORD_OF prefixes are cut at a uniformly chosen depth from the study node to the leaf.
- A search entry with a null term asks for every concept, which the service answers as an
  array of one-entry objects.

For a consent-partitioned store, pass one `--column-meta` per partition (they are merged as the
service merges its dictionary) and `--consents`:

```bash
hpds-bench corpus --column-meta store/p1/columnMeta.csv --column-meta store/p2/columnMeta.csv \
  --consents auto --seed 1 --out /tmp/corpus
hpds-bench corpus --column-meta ... --consents auto --partition-dir store \
  --consent-variants all,one,two,subset,unknown --out /tmp/corpus-required
```

`--consents` takes a comma-separated partition list, or `auto` for the subdirectories of
`--partition-dir` (holding `pheno.index`, `columnMeta.javabin` or `columnMeta.csv`), or else the
directory names of the `--column-meta` files. Each query is then emitted with `userConsents`
variants, named `<entry>-c-<variant>`: `all`, `one`, `two`, `subset` (a random subset, half of the
time with an unknown consent mixed in), `unknown`, `empty` (`[]`) and `none` (no field). Fixed
entries get every variant, generated entries `--variants-per-entry` (3) of them in rotation.
Leave out `empty` and `none` for a service that requires consents when every query should
carry some. Error-path entries carry every partition, and three error-path entries with a
repeated key (`userConsents` twice, `value` twice) are added.

## Compare

```bash
hpds-bench compare --a http://127.0.0.1:18081/PIC-SURE --b http://rust:8080/PIC-SURE \
  --corpus /tmp/corpus --divergences bench/divergences.json --retries 1
hpds-bench compare --a http://127.0.0.1:18081/PIC-SURE --golden bench/golden/1000genomes.json
```

Responses with status 400 or above on both sides are compared by status only. JSON compares by
structure, ignoring key order, and `1` equals `1.0`, except in count-valued sync results (COUNT
and the cross counts), where an integer against a float is a difference because Java emits
integers. CSV compares the header exactly and the rows as a multiset; a row that fails to parse
or has the wrong field count is a difference.

A difference matching a rule in the divergence file prints as `WHITELISTED` with the rule's
reason and does not fail the run. A rule names an entry glob, a list of exact entry names, a result type, an unordered status
pair, or a condition: `strict-subset-or-timeout`, `success-body-empty` (every side that
answered 200 sent an empty body), `numeric-max-double-min-value` (the JSON bodies are equal once
every `max` of `4.9E-324` on one side, against zero or below on the other, is aligned), or
`range-min-success-body-empty` (both sides answered a sync request with 200, exactly one with an
empty body, and the entry's phenotypic clause holds a `FILTER` with a `min`); the full list is
the `Condition` type in `src/divergence.rs`. A rule with `javaBranch` (`main` or `consent`)
applies only when the run names that branch with `--java <a|b> --java-branch <main|consent>`,
which also tells conditions that need it which answer is Java's (`authorization-filters-narrow`
accepts only a narrower answer from main):

```bash
hpds-bench compare --a http://127.0.0.1:18081/PIC-SURE --b http://rust:8080/PIC-SURE \
  --corpus /tmp/corpus --divergences bench/divergences.json --java a --java-branch main
```

`--retries N` retries other differences; an
entry that recovers is listed as `FLAKY` with the side that answered differently between
attempts.

## Load

```bash
hpds-bench load --url http://127.0.0.1:18081/PIC-SURE --corpus /tmp/corpus \
  --concurrency 8 --duration 60s --warmup 10s
hpds-bench load --url ... --corpus /tmp/corpus --concurrency 8 --passes 3
```

The load is closed loop: each worker sends its next request only after the previous one
finished, so a slow server is offered fewer requests, and no coordinated omission correction is
applied. Tail percentiles are therefore a lower bound on what independent clients would see.

- Async queries are polled every 2 ms (`--poll-interval`).
- Per request the report records total latency, service time (submit to terminal status for
  async entries, request to response headers otherwise) and download time (result fetch and
  body drain). Result bodies are streamed and counted, not kept.
- Throughput is requests over the span from the first measured start to the last measured finish.
- Timeouts are counted apart from errors. A second latency view records each timeout at the
  `--timeout` value (`with_timeouts`).
- `--passes N` runs every entry once per pass and reports each pass; pass 1 is the cold pass.
  The top-level `overall` and `per_type` then cover all passes together.
- Async `picsureId` values are derived from the worker index, so they are unique among in-flight
  requests while the Java result map stays bounded.

## Pair parity

`pair-parity.sh` checks that two `hpds-rs` builds answer alike, for example a release against
the one before it on the store each reads, or one build on a store loaded from CSV against the
same data converted:

```bash
A_BIN=old/hpds-rs A_STORE=/stores/old B_BIN=target/release/hpds-rs B_STORE=/stores/new \
  BENCH=target/release/hpds-bench bench/pair-parity.sh /tmp/pair-out /tmp/corpus-1 /tmp/corpus-2
```

Both builds are started natively on `127.0.0.1` with the same settings. On every corpus it runs
`hpds-bench compare` (entries that agree only after a retry are counted as flaky, since a
nondeterministic answer is a finding), then two checks `compare` cannot make:

- `async-compare.py` runs every async entry on both and compares the final status,
  `sizeInBytes` and error, the `/result` status and headers except `Date`, and the body by
  SHA-256. `compare` reads CSV rows as a multiset; this check pins their order and every byte.
- `search-encoding.py` sends every search entry to A with no `Accept-Encoding` header, then to
  B with none and with eight values. With `SEARCH_GZIP=off` every B answer must equal A's
  exactly; with `SEARCH_GZIP=on` (and `HPDS_SEARCH_GZIP=on` in `B_ENV`) the gunzipped body
  must equal A's, gzip must be used exactly when accepted on bodies of at least 1,400 bytes,
  and `Vary` and `Content-Length` must be right.

`RESULT_CACHE_BYTES` with `PASSES=2` runs every corpus twice against warm result caches.
`GOLDEN` checks golden files against both sides. The run exits 1 on any differing or flaky
entry, any failed async or search check (a timeout on either side included), a golden file
the two sides fail on different entries, or a WARN or ERROR line in B's log; a side that
exits or reports DOWN before it is UP stops the run at once. The usage text in the script
lists every setting.

## ETL parity

```bash
WORK_DIR=/tmp/parity EDGE_DIR=<edge inputs> bench/etl-parity.sh            # light cases
DEV_DIR=... DEV_PATIENT_DIR=... AIO_DIR=<local_all_in_one> bench/etl-parity.sh aio dev-newsearch
bench/etl-parity.sh --list
```

Each case loads the same CSV input with a Java loader (`CSVLoaderNewSearch` or
`LowRAMMultiCSVLoader`) and with the matching `hpds-etl load --mode`, then compares:

- the canonical dumps, Java `DumpSourceCSV` against `hpds-etl dump`, concept by concept. Rows
  match on patient, value (numbers by their parsed bits, every NaN equal) and timestamp. The
  first line of each dump is skipped, because Java's header line is the `toString` of an array.
  Rows that match as a multiset but not in order are a difference, since within-patient order
  is part of the contract;
- `columnMeta.csv`, every column but 8 and 9 (block offset and length), min and max by value;
- the patient ID sets. Java's `allIds` is read by migrating the Java store with
  `bin/migrate-javabins` and running `hpds-etl dump --patients` on the result. The migrated
  store is also compared with the Java dump, so every case doubles as a migration check
  (`<case>-migration`).

`etl-parity-compare.py` prints each difference as `WHITELISTED` with the rule from
`etl-divergences.json`, its reason and the spec text it cites, or as `UNEXPECTED`. Any
unexpected difference fails the run. Rules that depend on the Java loaders' data loss use a
model of how the named loader ingests the files: its row order, its loading cache (16
concepts for new-search, 1 for LowRAM) and LowRAM's chunking. A rule applies only when the
model says the loss can reach that concept or patient and every Java row is also in the Rust
store.

Reports (`<case>.json`, `<case>.txt`, `summary.tsv`) go to `results/etl-parity-<date>/`;
stores and dumps go to `WORK_DIR`. The `aio` case copies the AIO's `hpds_input` files and
javabin store into `WORK_DIR` with `cp` (nothing in `AIO_DIR` is mounted or written), writes
counts-only reports, keeps the migrated store at `AIO_MIGRATED_OUT` and deletes every other
AIO-derived file when it ends. Java containers are named `parity-<case>-<step>`.

## Partitioned ETL parity

```bash
bench/etl-parity-partitioned.sh                         # harm-fixture, edge, edge-config
bench/etl-parity-partitioned.sh dir=/path/to/partition-files
DEV_INPUT_DIR=... DEV_JAVA_STORE=... DEV_RUST_STORE=... bench/etl-parity-partitioned.sh dev
```

Each case loads a directory of headerless `<partition>-allConcepts.csv` files with the
harmonization `CSVLoader` (`hms-dbmi/pic-sure-hpds-etl:harm`) and with
`hpds-etl load --mode partitioned`, checks that both wrote the same partitions, and compares
each partition as the unpartitioned harness does (dumps, `columnMeta.csv`, patient sets, and
the migrated Java partition against the Java dump), with loaders `partitioned` and
`migration-partitioned`. `DumpSourceCSV` runs on a copy of each Java partition, so a prebuilt
store given for `dev` is only read. Java containers are named `v2-par-etl-<case>-<step>` and
capped at `JAVA_MEMORY` (6g).

## Container

`hpds-bench` ships in the `hms-dbmi/pic-sure-hpds-rs-etl` image next to `hpds-etl`:

```bash
docker run --rm --network <aio-network> --entrypoint hpds-bench hms-dbmi/pic-sure-hpds-rs-etl:LATEST \
  load --url http://hpds:8080/PIC-SURE --corpus /corpus
```
