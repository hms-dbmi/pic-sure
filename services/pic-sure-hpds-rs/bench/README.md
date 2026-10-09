# bench

Harness for comparing the Java HPDS service with the Rust one, and for measuring either.
Everything here drives `hpds-bench` (`crates/hpds-bench`) over the `PIC-SURE/v3` HTTP contract.

| Path | Purpose |
|---|---|
| `java-fixture.sh` | Builds the 1000 Genomes phenotype store with the Java ETL and runs the Java service on it |
| `divergences.json` | Accepted differences between Java and Rust, applied by `compare --divergences` |
| `golden/1000genomes.json` | Expected answers for the fixture, checked by `compare --golden` |
| `baseline.sh`, `docker-compose.yml` | Java baseline stack and profiling matrix |

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
pair, or a condition: `strict-subset-or-timeout`, or `success-body-empty` (every side that
answered 200 sent an empty body). `--retries N` retries other differences; an
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

## Container

`hpds-bench` ships in the `hms-dbmi/pic-sure-hpds-rs-etl` image next to `hpds-etl`:

```bash
docker run --rm --network <aio-network> --entrypoint hpds-bench hms-dbmi/pic-sure-hpds-rs-etl:LATEST \
  load --url http://hpds:8080/PIC-SURE --corpus /corpus
```
