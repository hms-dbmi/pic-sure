# Local PIC-SURE stack

A layered PIC-SURE stack that runs on one machine, with a way to check that what the loaders put into HPDS is what the input says.
The first layer is `hpds`: phenotype data loaded by three different routes, each served by its own HPDS container, and a
comparison of all of them with a ground truth computed from the input.

```
make -C tools/local-stack run        # build, load the `combined` dataset through every lane, start HPDS, compare, print the URLs
make -C tools/local-stack down       # stop and remove the containers
```

Later layers (`api`, `auth`, `ui`) plug into the same Makefile and `docker-compose.yml` as further compose profiles.

## Prerequisites

Docker (with `docker compose`), Java 25, Maven, Python 3.9 or newer (standard library only), GNU make 3.81 or newer, git. The first
`make` builds the jars (a few minutes) and the HPDS image; later runs reuse them (`make jars REBUILD=1` rebuilds).

Memory: the HPDS containers get `-Xmx1g` each and the loaders `-Xmx2g` (`HPDS_JAVA_OPTS`, `LOADER_XMX`). Host ports 8081 to 8083 must
be free; change them in `local.env`.

## Layers

| layer | what | compose profile | status |
|---|---|---|---|
| `hpds` | one HPDS per loader lane, on :8081 (lane1), :8082 (lane2), :8083 (lane3) | `hpds` | now |
| `api` | PIC-SURE API in front of HPDS | `api` | planned (T27b) |
| `auth` | authentication | `auth` | planned |
| `ui` | PIC-SURE frontend | `ui` | planned |

`make up LAYERS=hpds` starts the layers it names (`_up-<layer>` targets and compose profiles of the same name). `make run` is
`make compare DATASET=combined` plus the URLs.

## Datasets

All data is synthetic. Accessions are the placeholders `phs000000`, `phs999999` and `phs123456` (plus the bare consent
`tutorial-biolincc_test`); patient numbers are made up.

| dataset | kind | what | lanes |
|---|---|---|---|
| `mini` | split | the five partitions of the hpds-pheno-load smoke test, copied to `fixtures/mini/` (see its README). `phs000000.c2` is deliberately malformed: a 3-column row, a 6-column row, a blank line and an invalid byte | 2, 3 |
| `1000g` | split | the four partitions of the HPDS test resources (`services/pic-sure-hpds/service/src/test/resources/phenotypic`), linked, not copied | 2, 3 |
| `combined` | combined | `gen-combined.py`: 80 patients, about 1000 rows in the shape of `allConcepts_data_analyzer.csv` | 1, 2, 3 |
| `synth` | combined | `gen-synth.py --patients N --concepts M --seed S` (defaults 200, 12, 7; `make compare DATASET=synth PATIENTS=2000 CONCEPTS=40 SEED=3`) | 1, 2, 3 |

`combined` and `synth` carry `\_consents\` rows for `phs000000.c1`, `phs000000.c2`, `phs999999.c1`, `phs123456.c1` and
`tutorial-biolincc_test`; a patient in two consents of one study and one in two studies; patients without a consent; global concepts
(`\DCC Harmonized data set\...`) and a node that is not a study (`\unconsented_study\...`); numeric and categorical concepts, numbers
written in the text column, text with a comma and quotes, repeated observations, a value that is also the last path node; timestamps
as ISO-8601, epoch milliseconds, `0` and empty; one header row in the middle and one `2402L` value (the splitter quarantines both);
and rows in random order. The keys are the committed test-only key of `fixtures/mini` (and, for `1000g`, the key next to its data).
They protect nothing.

## Lanes

Named like the flows of the future runner. Every lane ends with a clean store (`work/<dataset>/<flow>/store`: the key and one
directory per partition) that the lane's HPDS mounts read-only at `/opt/local/hpds`.

| lane | flow | route |
|---|---|---|
| 1 | `picsureloader-combined` | PICSURELoader reads the combined file and routes it by consent itself (`-Dpicsure.loader.input=<file> -Dpicsure.loader.partitionBy=consent`) |
| 2 | `csvloader-split` | splitter, then `LC_ALL=C sort -t, -k2` per partition, then one CSVLoader JVM per partition |
| 3 | `picsureloader-split` | splitter, then PICSURELoader on all partitions (unsorted, `failFast=false`) |

The splitter is `split-allconcepts-by-consent` of pic-sure-hpds-etl at commit `6416d91` (`SPLITTER_REF`), extracted with `git archive`
into `work/src/hpds-etl` and built there; the checkout is never written to. Options: `--globals=by-patient --partition-naming=suffix
--timestamps=convert-iso --zero-timestamps=blank --bad-rows=quarantine --max-reject-fraction=0.05 --force`. If pic-sure-hpds-etl is not
next to this checkout, pass `SPLITTER_REPO=<path>` or `SPLITTER_JAR=<hpds-etl.jar>`.

Lane 1 needs a PICSURELoader that knows the combined-file options. The lane checks the jar (the string `partitionBy` in `LoaderConfig`)
and otherwise prints `lane1 skipped: PICSURELoader without combined input`; the report shows it as skipped. Split datasets (`mini`,
`1000g`) have no combined file and run lanes 2 and 3 only.

Both loaders write next to their input, so a lane always works on copies or links under `work/`.

## Make targets

| target | what |
|---|---|
| `make jars` | both loaders (`mvn -pl services/pic-sure-hpds/etl -am package`, into `services/pic-sure-hpds/docker/pic-sure-hpds-etl/`, which git ignores), the HPDS service jar, the splitter |
| `make split DATASET=` | generate or link the input, run the splitter |
| `make load DATASET= [FLOWS=]` | run the flows in parallel (`make -j`); each writes `work/<dataset>/<flow>/load.json` (wall time, exit code, partitions loaded and failed, store size, the PICSURELoader's per-partition counts) and `load.log` |
| `make up [LAYERS=hpds] DATASET=` | build the HPDS image, (re)start the lanes' containers on that dataset's stores and wait for `/actuator/health` |
| `make compare DATASET=mini\|1000g\|combined\|synth\|all` | `jars`, then `split`, `load`, `up` and `compare.py run` per dataset, then the report. Exit status 1 on an unexplained mismatch |
| `make run` | `make compare DATASET=combined`, then the URLs and the report path |
| `make down` / `restart` | stop and remove the containers (`restart` = down + up) |
| `make clean` / `clean-data` | remove `work/` / remove the datasets and the report but keep the splitter build and the image |

`DATASET=all` leaves the stack serving the last dataset (`synth`). `make up DATASET=mini` switches it. Python:
`python3 -m unittest discover tools/local-stack/tests`.

## Querying a lane

```bash
curl -s -X POST http://localhost:8082/PIC-SURE/v3/query/sync -H 'Content-Type: application/json' -d '{
  "query": {
    "userConsents": [{"value": "phs000000.c1"}],
    "phenotypicClause": {"phenotypicFilterType": "REQUIRED", "conceptPath": "\\DCC Harmonized data set\\age\\"},
    "expectedResultType": "COUNT"
  }
}'
```

- `userConsents` picks the partitions (one directory per consent); a consent HPDS does not have is skipped silently and answers 0. A
  query without `userConsents` answers an empty body, not a count (`hpds.requireAuthorizationFilter` is on).
- `FILTER` with `"values": ["Yes"]` for a categorical concept, with `"min": 3.0, "max": 9.0` for a numeric one (both inclusive; either may
  be left out). A `values` filter on a numeric concept answers an empty body, so the battery never mixes the types.
- `/query/sync` serves `COUNT`, `CROSS_COUNT`, `CATEGORICAL_CROSS_COUNT`, `CONTINUOUS_CROSS_COUNT` and `OBSERVATION_CROSS_COUNT`.
  `DATAFRAME` answers 400 there: submit it with `POST /PIC-SURE/v3/query` (with a `select` list), poll
  `/query/{resourceResultId}/status` and read `/query/{resourceResultId}/result`. That needs `ID_BATCH_SIZE`, set to 1000 by the compose
  file.

## Pointing at any other store

A store is a directory with `encryption_key` and one subdirectory per partition holding `allObservationsStore.javabin`,
`columnMeta.javabin` and `columnMeta.csv`. The key must be at `/opt/local/hpds/encryption_key`.

```bash
docker run --rm -p 8090:8080 -e JAVA_OPTS=-Xmx1g -e PICSURE_ACTUATOR_EXPOSURE=health \
  -v /path/to/store:/opt/local/hpds:ro picsure-local-hpds:local
```

(`make up` builds the image `picsure-local-hpds:local` from the repository's own Dockerfile.) To compare a running HPDS on another
host or port with a dataset's truth: `python3 compare.py run combined --host other-host --lane2-port 9000`.

## Report

`work/report/compare.md` (for people) and `compare.json` (for machines) cover every dataset run in the same `make` call;
`work/report/<dataset>.json` is the per-dataset result.

What is compared, for each lane that loaded something:

1. **The partitions**: a lane that did not load a partition the input has, or loaded one it does not.
2. **columnMeta.csv** of each partition against the truth (categorical flag, category values, min, max, observation count, patient
   count) and the lanes' columnMeta files against each other with the offset and length columns dropped.
3. **Queries** on each lane's HPDS (`COUNT`): for each partition (one consent), for all partitions together, and for an unknown consent
   (expected 0). `REQUIRED` per concept; `FILTER` per categorical value (at most 6 per concept, plus all values together) and values that
   only a store's columnMeta lists; `FILTER` on numeric concepts for `[min, max]`, `[min, mid]`, `[mid, max]` and above the maximum.
4. For combined datasets, the splitter's `split_manifest.json` (rows and patients per partition, rejected rows) against the truth's own
   routing.

The truth is computed from the input in `pheno.py`, in Python, independently of the splitter and the loaders: the splitter's row
rules and routing (`--globals=by-patient`), the loaders' row semantics, and HPDS's query semantics. It is checked against
`fixtures/mini/expected/*.tsv` (computed by `gen-fixtures.sh` without any loader) in the unit tests.

A difference that matches a known difference (below) is **explained**; everything else is **unexplained** and makes the exit status 1,
as does a query that is not answered with a count. The report lists the unexplained mismatches first, then the explained ones with the
known difference that covers them, then each known difference with the evidence found in the run.

## Known differences

These are written from evidence found by running the stack (the report repeats them with the counts of the run).

- **`fffd-in-text`: CSVLoader keeps U+FFFD in a text value, PICSURELoader removes it.** `mini` partition `phs000000.c2` has `bad<0xFF>byte`
  as a value. Java reads the byte as U+FFFD. CSVLoader stores `bad<U+FFFD>byte` (width 10), PICSURELoader `badbyte` (width 7), so a
  query for `badbyte` finds the patient only in lane 3, and the category lists differ. The truth is the PICSURELoader result. This is
  the "malformed partition" of `mini`: it loads in both lanes, and what differs is explained.
- **`short-rows`: a row with fewer than four columns is skipped by both loaders** (mini's 3-column row). The PICSURELoader records it as
  `MALFORMED_RECORD`, the CSVLoader only logs it. Both stores hold the same data. Six columns, a blank line and the invalid byte are
  tolerated by both.
- **`rollup-last-node`: a last path node equal to the text value is dropped by both loaders** (`\a\b\Yes\` with value `Yes` becomes
  `\a\b\`). The truth applies the rule; it is listed because it surprises people querying by full path. `combined` and `synth` contain
  such concepts.
- **`no-value-rows`: a row with neither value stores nothing** in both. The PICSURELoader reports it as `MISSING_VALUE`.
- **`csvloader-empty-row-first`: the CSVLoader types a concept by its first row.** After the sort, an empty-valued row of a numeric
  concept comes first, the concept becomes categorical and all its numeric values are dropped (0 observations); a concept with only
  empty rows is stored with 0 observations. Tried on a three-row file: CSVLoader 0 observations, PICSURELoader 2. The datasets avoid
  empty rows on numeric concepts so the lanes stay comparable; the comparison explains it when real data has it.
- **`splitter-quarantine`: the splitter sets rows aside**: a header row in the middle, `2402L` as nval, and rows of patients without a
  consent. The truth routes the rows independently and agrees with `split_manifest.json`. Lane 1 does this routing inside the
  PICSURELoader and agrees with the truth too (it reports `MISSING_VALUE` and `NUMERIC_PARSE_ERROR` rows instead of quarantining).
- A CSVLoader without the hyphen fix (`CSVLoader.partitionNameFor`, branch `csvloader-partition-names`) names `tutorial-biolincc_test`
  `tutorial`: the lane shows 4 of 5 partitions loaded and the comparison reports the missing partition.
- CSVLoader needs input sorted by concept (16 cached cubes) and loses data silently otherwise; lane 2 sorts, the PICSURELoader does not
  need it.

## Files

| file | what |
|---|---|
| `Makefile`, `docker-compose.yml`, `.env.defaults` | the stack; ports, hosts and image in `.env.defaults`, per-machine overrides in `local.env` (ignored by git) |
| `lane.py` | split step and loader lanes |
| `compare.py` | queries, columnMeta checks, report |
| `pheno.py` | the ground truth |
| `datagen.py`, `gen-combined.py`, `gen-synth.py` | synthetic input |
| `fixtures/mini/` | the mini dataset and its key (test only) |
| `tests/` | unit tests |
| `work/` | everything built or loaded (ignored by git) |
