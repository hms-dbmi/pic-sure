# Local PIC-SURE stack

A layered PIC-SURE stack that runs on one machine, with a way to check that what the loaders put into HPDS is what the input says.
The first layer is `hpds`: phenotype data loaded by three different routes, each served by its own HPDS container, and a
comparison of all of them with a ground truth computed from the input. The second, `api`, puts the PIC-SURE query-service
(with the operations-service and MySQL behind it) in front of one of those HPDS containers and checks that it changes nothing. The
third, `dict`, runs the dictionary service on Postgres. `qa.py` (`make qa`) is a headless battery over the whole stack, and
`DATASET=dir` serves a partition set that already exists (no loader runs), which is how a real dataset is checked in the boundary.

```
make -C tools/local-stack run                   # build, load the `combined` dataset through every lane, start HPDS, compare, print the URLs
make -C tools/local-stack run LAYERS=hpds,api   # the same, plus the API in front of lane 3's HPDS and the same queries sent through it
make -C tools/local-stack run LAYERS=hpds,api,dict   # ... and the dictionary (Postgres + service) with a synthetic dictionary
make -C tools/local-stack qa                    # the headless QA battery against the stack that is up (exit 0 PASS, 1 FAIL, 2 inconclusive)
make -C tools/local-stack qa-scenarios          # the three QA scenarios (phenotype only; with genomic data; genomic directory empty), see QA scenarios
make -C tools/local-stack down                  # stop and remove the containers
make -C tools/local-stack test                  # the unit tests of the Python helpers (no Docker)
```

Lane 1 (PICSURELoader reading the combined file) only runs with a PICSURELoader that has the combined-input options (T25b, branch
`picsure-loader`). With the jar of a branch that lacks them the stack skips lane 1, says so on the result line and runs lanes 2 and 3.

Later layers (`auth`, `ui`) plug into the same Makefile and `docker-compose.yml` as further compose profiles.

## Prerequisites

Docker (with `docker compose`), Java 25, Maven, Python 3.9 or newer (standard library only), GNU make 3.81 or newer, git. The first
`make` builds the jars (a few minutes) and the images; later runs reuse them (`REBUILD=1` builds again: `make jars REBUILD=1` every jar the run needs, that is the served services and the CSVLoader of their refs, PICSURELoader
from this checkout and the splitter; `make up REBUILD=1` the jars and images of the layers it starts, and the `RemoveConceptFromMetadata` jar when it
generates an open columnMeta; `make load REBUILD=1` the CSVLoader).

The path of the checkout must not contain spaces (make cannot handle them; the Makefile stops with an error).

Memory: the HPDS containers get `-Xmx1g` each and the loaders `-Xmx2g` (`HPDS_JAVA_OPTS`, `LOADER_XMX`); with layer `api` the two
Spring services get `-Xmx512m` each (`API_JAVA_OPTS`) and MySQL runs in memory. Host ports 8081 to 8083 (and with layer `api` 8091, 8092
and 33306) must be free; change them in `local.env`. Layer `api` also pulls `mysql:8.4` and `flyway/flyway` (pinned in `.env.defaults`,
both multi-arch) the first time. The open-mode HPDS (`OPEN=1`, the default) is a further `-Xmx1g` JVM on :8085, and `DATASET=dir` serves
from :8084. Layer `dict` adds Postgres (`postgres:16-alpine`, 127.0.0.1:55433) and the dictionary service (`-Xmx768m`, :8095).

## What is tested

The stack tests **the service code of one commit**, not the code of the checkout it runs from. `SERVICES_REF` (default
`csvloader-partition-names`, the ref the deployed load and dev use) names that commit, and every container the stack serves is built from it:

| served | built from | variable (default) |
|---|---|---|
| the HPDS service image: `hpds-lane1..3`, `hpds-dir`, `hpds-open` | `services/pic-sure-hpds/service` of the commit | `HPDS_REF` (`SERVICES_REF`) |
| query-service and operations-service (and the operations-service's schema migrations) | `services/pic-sure-hpds-query-service`, `services/pic-sure-operations-service` | `QUERY_REF` (`SERVICES_REF`) |
| the dictionary service | `services/picsure-dictionary` | `DICT_REF` (`SERVICES_REF`) |
| the CSVLoader of lane 2 and `RemoveConceptFromMetadata` (the open columnMeta) | `services/pic-sure-hpds/etl` | `LOADER_REF` (`SERVICES_REF`) |

What comes from the checkout this branch is on, and is therefore not under test:

- **The tooling**: the Makefile, `lane.py`, `compare.py`, `qa.py`, the fixtures and the checks. Their commit is recorded with every run.
- **PICSURELoader (lanes 1 and 3)**. It is not on `csvloader-partition-names`, so it is built from the checkout. The loaders only produce
  synthetic test input; what is under test is the HPDS that serves their stores. The checkout's `data` module (which the loader jar bundles) can
  differ from the ref's; the stores it writes are read by the ref's HPDS (see Loader and HPDS compatibility).
- The splitter (`SPLITTER_REF`, a pinned commit of pic-sure-hpds-etl) and `DATASET=dir`, which has no loader at all: it serves the partition
  set you give it.

A ref is anything `git rev-parse --verify <ref>^{commit}` resolves: a local branch, `origin/<ref>`, `upstream/<ref>` (tried in that order, except `csvloader-partition-names`, the ref the label names, which is tried
`upstream/`, `origin/`, local so that the build and the label agree), a
tag or a sha. A ref that resolves to none of them stops the build with the names it tried. Examples:

```
make run LAYERS=hpds,api,dict                           # SERVICES_REF=csvloader-partition-names
make run LAYERS=hpds,api,dict SERVICES_REF=feature-harmonization
make up  LAYERS=hpds,api,dict DICT_REF=upstream/main   # only the dictionary comes from another commit (dev may run it from main)
make load DATASET=combined LOADER_REF=<sha>            # the loaders from a commit of their own (see below)
```

`LOADER_REF` is used in two places: `make up` with a synthetic dataset runs its `RemoveConceptFromMetadata` for the open columnMeta, and `make load`,
`make compare` and `make run` run its CSVLoader for lane 2.

Set them on the command line or in `local.env` (`SERVICES_REF` is in `.env.defaults`; `HPDS_REF`, `QUERY_REF`, `DICT_REF`, `LOADER_REF` are unset
and follow it). The commit is extracted with `git archive` into `work/src/services-<sha>/` and built there with the same maven flags as before
(`mvn -B -ntp -q -pl <modules> -am package -DskipTests`, one call per commit); the checkout is never written to by that step. The cache key
is the sha: a second run with a ref that resolves to a built commit builds nothing, a commit that moved builds in a new directory, and
`make jars REBUILD=1` builds again. Each image is labelled `local-stack.services-sha` and is rebuilt when the label is not the sha the jar came from.

**Provenance.** `make qa` and `make compare` print, and write into `qa.json` (`summary.provenance`), `compare.json` (`provenance`) and
`compare.md`, what is **running**: the `local-stack.services-sha` label of the image each running container was created from (the HPDS
containers of the stack, `picsure-local-query`, `picsure-local-operations`, `picsure-local-dict-api`; `make qa` asks the container of `STORE`
and the open-mode one). A container that was replaced or stopped changes the lines at once.

```
services: hpds=cbf78dd1677d query=cbf78dd1677d dict=cbf78dd1677d
tooling: <commit of this checkout at the time of the report>[-dirty]
tests: csvloader-partition-names
```

- `work/services.json`, written by `make up` and `make jars` with the ref, the sha and the candidate that matched (the ref itself, `origin/<ref>`
  or `upstream/<ref>`), is only a cross-check: a container whose sha differs from it is printed as `warning: ...` and kept in the report. The
  matched candidate is also a label of the image (`local-stack.services-via`).
- `loader=<sha>` is added to the `services:` line when the run used loader output: the lane-2 stores (the CSVLoader jar is recorded in the
  lane's `load.json`; a compare reports it when lane 2 ran, and `make qa STORE=lane2` reports it because the served store, and the open root
  built from it, were written by it) and the open columnMeta that `make up` generates for a synthetic dataset (`work/<dataset>/open-columnmeta.jar`
  names the `RemoveConceptFromMetadata` jar, and is removed when `make up` starts the open HPDS with `OPEN_COLUMNMETA_DIR`). A lane-2 store whose
  `load.json` is missing or names no jar is an unknown loader: it reads `loader=checkout` and the `tests:` line says `NOT`. Lanes 1 and 3 use
  PICSURELoader from the checkout and are not part of it. `compare` queries the lane HPDS containers and the query-service (its auth path, in
  front of one lane), never the open-mode HPDS, so it has no open-columnMeta loader to report.
- The open-mode HPDS and the query-service carry a `local-stack.store` label (the `STORE` they were started with). `make qa` reads it from the
  running containers, not from its own `STORE`: a container that serves lane 2 counts the lane-2 loader, and the HPDS behind the query-service is
  part of the `services:` line. A container that serves another store than the one `make qa` compares with (or a query-service whose
  `HPDS_AUTH_URL` points at another HPDS) is a setup problem (`QA INCONCLUSIVE`, exit 2) and is not compared; one without the label makes the
  `tests:` line `unknown`.
- `tests:` names `csvloader-partition-names` only when every running service and every loader that was used is at that ref's commit. The target is
  resolved as `upstream/<ref>` first, then `origin/<ref>`, then the local branch, and the build resolves `csvloader-partition-names` the same
  way (any other ref is tried as given, then `origin/`, then `upstream/`). Otherwise it says
  `NOT csvloader-partition-names (dict=b05ba6ad2fd5, not cbf78dd1677d)` (a `LOADER_REF` at another commit does the same), and `unknown` when a
  service has no running container or a container without the label (not started through make).
- A tooling checkout with uncommitted changes in `tools/local-stack` qualifies the label and does not block it:
  `csvloader-partition-names (tooling <sha>-dirty)`.

### Loader and HPDS compatibility

Lanes 1 and 3 write stores with the checkout's PICSURELoader (and the checkout's `data` module, `PhenoCube`, `ColumnMetaBuilder`), and the
`SERVICES_REF` HPDS reads them. The `data` differences on `feature-harmonization` against `csvloader-partition-names` are not format changes (a bounds fix in
`PhenoCube.seekForMinIndex`, and a new helper class), and the stack shows it: all lanes load and answer the same counts as the truth on the
deployed HPDS. If a store did not load in the ref's HPDS, the HPDS container would fail to start or the queries would fail, and
the report would say so; nothing in the stack hides that. See Known differences for the one query-level difference this found.

## Layers

| layer | what | compose profile | status |
|---|---|---|---|
| `hpds` | one HPDS per loader lane, on :8081 (lane1), :8082 (lane2), :8083 (lane3) | `hpds` | now |
| `api` | MySQL, operations-service and query-service in front of the HPDS of one lane (`STORE`), on :8091 (query), :8092 (operations), 127.0.0.1:33306 (MySQL) | `api` | now |
| `dict` | Postgres 16 (database `picsure`, schema `dict`, on a tmpfs) and the picsure-dictionary service, on :8095 (service), 127.0.0.1:55433 (Postgres) | `dict` | now |
| `auth` | authentication | `auth` | planned |
| `ui` | PIC-SURE frontend | `ui` | planned |

`make up LAYERS=hpds,api,dict` starts the layers it names (`_up-<layer>` targets and compose profiles of the same name; `LAYERS` is comma or
space separated). They always start in the order `hpds`, `api`, `dict`: `up` begins by removing the whole stack, and the API needs the HPDS
containers it points at. The `dict` layer does not need the others (`LAYERS=dict` alone works) and only replaces its own containers. `make run` is `make compare DATASET=combined` plus the URLs; `make run LAYERS=hpds,api` also starts the API and
adds `compare.py --via=api` to the comparison (see Layer api). `LAYERS=api` without `hpds` is refused by the Makefile (the API queries a
lane's HPDS).

## Layer api

```
make up LAYERS=hpds,api DATASET=combined STORE=lane2     # lane 2's HPDS behind the API (STORE defaults to lane3)
make run LAYERS=hpds,api                                 # build, load, start, compare the lanes, then compare the API with the HPDS
make restart LAYERS=hpds,api STORE=lane1                 # down, then up again with the API serving another lane
```

Containers: `picsure-local-mysql` (`mysql:8.4`, database `picsure`, on a tmpfs, so nothing survives `make down`), `picsure-local-flyway`
(runs `services/pic-sure-operations-service/db/sql/V1..V8` plus `api/sql/V9000`, then exits), `picsure-local-operations`
(operations-service, jar from `services/pic-sure-operations-service`) and `picsure-local-query` (query-service, jar from
`services/pic-sure-hpds-query-service`). The images are built from the services' own Dockerfiles with a context that holds just the jar; the
jars are built from `QUERY_REF` (see What is tested; `make jars REBUILD=1` rebuilds them).

| what | default | variable |
|---|---|---|
| query-service | `http://localhost:8091` (`POST /hpds/auth/v3/query/sync`, `GET /actuator/health`) | `API_QUERY_PORT` |
| operations-service | `http://localhost:8092/operations` (`/operations/actuator/health`) | `API_OPERATIONS_PORT` |
| MySQL | `127.0.0.1:33306`, database `picsure`, user `picsure`, password `picsure-local` (root: `root-local`); always loopback only | `API_MYSQL_PORT`, `API_DB_PASSWORD` |
| lane served | `lane3` (`picsureloader-split`) | `STORE=lane1\|lane2\|lane3` |

**How `STORE` works.** The query-service has one HPDS per backend; `HPDS_AUTH_URL` and `HPDS_OPEN_URL` are both set to
`http://hpds-<STORE>:8080/PIC-SURE` (the service appends `/v3`), so the API serves one lane's store at a time. `STORE` has to be a lane that
has a store for the dataset (lane 1 does not for the split datasets, or with a PICSURELoader without the combined-input options). Changing
it means `make restart STORE=...` (or `up`), which recreates the stack.

**What the API is configured to do.**

- `CONSENT_BASED_AUTHORIZATION_ENABLED=false`: with it on, the auth backend replaces the query's `userConsents` with what PSAMA says the bearer
  token may see, and there is no PSAMA here. With it off the consents in the query go to HPDS as they are, which is what the comparison needs.
- Identity: the query-service does no token check; it takes the caller from the `X-User-Id` header and answers 403 without it. The stack sends
  `X-User-Id: local-stack` (`API_USER_ID`).
- The query-service saves every query it answers through the operations-service (shared secret `API_INTERNAL_TOKEN`, header
  `X-PIC-SURE-INTERNAL-TOKEN`), after HPDS has answered. A failed save is not swallowed: the caller gets 502 and no count. So a query
  answered here is a query that is in the `query` table of MySQL, with `version` 3.

```bash
curl -s -X POST http://localhost:8091/hpds/auth/v3/query/sync -H 'X-User-Id: me' -H 'Content-Type: application/json' -d '{
  "query": {
    "userConsents": [{"value": "phs000000.c1"}],
    "phenotypicClause": {"phenotypicFilterType": "REQUIRED", "conceptPath": "\\DCC Harmonized data set\\age\\"},
    "expectedResultType": "COUNT"
  }
}'
```

The body is the one HPDS takes (see Querying a lane); the answer is the same bytes, plus a `queryMetadata` header with the query id.

**Known quirks.**

- The missing-consents case is an **empty 200**, not an error: a query without `userConsents` (or with an empty list) goes through the API to
  HPDS, which answers an empty body (see Querying a lane), and the API passes it on with status 200. The same goes for a consent HPDS does not
  have, which answers `0`. `compare.py` treats an empty body as "not a count", i.e. an unanswered query.
- No identity is a bare 403 with an empty body (Spring Security), not the service's JSON error.
- The operations-service's migrations `V1..V8` do not create the `version` column its `Query` entity writes, so without a fix every save
  fails with `Unknown column 'version'` and the query-service answers 502. The stack adds the column in `api/sql/V9000__LOCAL_QUERY_VERSION.sql`
  (only if it is not there). The monorepo needs a real migration for it.
- Flyway prints a screenful of `utf8mb3` deprecation warnings: they come from the 2017-era `V1` dump and do not matter.
- `INFO_COLUMN_LISTING` and anything else HPDS answers without a `queryMetadata` header is not saved.

**`compare.py --via=api`.** `make compare`/`run` with `api` in `LAYERS` calls `compare.py run <dataset> --via=api --store <STORE>`: the
usual HPDS comparison (every lane, direct), then the same battery through the query-service, query by query, against what the HPDS of the
same store answered directly. Any count that differs, any HTTP error or empty body where the HPDS answered, and an API that is down or not
serving the store are **unexplained mismatches** (category `api-vs-hpds`; no known difference ever explains one), so the exit status is 1.
Both sides are compared as answered, not against the truth: if the HPDS is wrong, the HPDS report says so, and the API is wrong only if
it differs.

Run outside make (`MAKELEVEL` unset), `compare.py run --via=api` first checks that the API it queries is wired to the store it was given:
`docker inspect picsure-local-query` must show `HPDS_AUTH_URL` with the host `hpds-<store>`. Otherwise (another store, or no such
container) it records an `api:setup` mismatch and compares nothing, instead of reporting differences between two different stores. Under
make the stack was started with the same `STORE`, so the check is skipped.

## Layer dict

```
make run LAYERS=hpds,api,dict                             # the dictionary of the synthetic `combined` dataset, then compare
make up LAYERS=dict DATASET=combined                      # only the dictionary
make up LAYERS=dict DATASET=dir DICT_DUMP=/path/dictionary_db_<stamp>.tar   # a real dictionary: restore a dump
```

Containers: `picsure-local-dict-db` (`postgres:16-alpine`, database `picsure`, user `picsure`, on a tmpfs, so nothing survives `make down`),
`picsure-local-dict-load` (fills the schema, then exits) and `picsure-local-dict-api` (`services/picsure-dictionary`; the image is built
from the service's own Dockerfile with a context that holds just its jar, the jar comes from the same maven reactor call as the others).

| what | default | variable |
|---|---|---|
| dictionary service | `http://localhost:8095` (`POST /concepts` with `{"consents": [...]}`, `GET /actuator/health`) | `DICT_API_PORT` |
| Postgres | `127.0.0.1:55433`, database `picsure`, user `picsure`, password `picsure-local`; loopback only | `DICT_DB_PORT`, `DICT_DB_PASSWORD` |
| Postgres image | `postgres:16-alpine`; at least the major version of the server that made a dump you restore (`pg_restore` refuses a newer archive) | `DICT_POSTGRES_IMAGE` |

**Where the content comes from.** Two ways, both end in schema `dict` of the one database:

- **Synthetic (default): a generated SQL fixture, no second repository.** `dictgen.py` writes `work/<dataset>/dict/schema.sql` (the
  service's own `db/schema.sql` and `db/flyway/V*.sql`, in version order) and `data.sql`, derived from the dataset's ground truth (the
  same `pheno.py` truth `compare.py` uses, computed from the input and independent of the loaders). So the dictionary matches the
  partitions of the dataset by construction: one dataset per study (`phs000000`), one consent row per partition (`c1`; a partition
  without a dot, `tutorial-biolincc_test`, has the empty consent code, which the service matches on the dataset ref), one concept node
  per concept path with interior nodes, `values`/`min`/`max` meta so the service lists it, and the `\DCC Harmonized data set\...`
  concepts under their own dataset, linked by `dataset_harmonization` to every study. `\_consents\` and the other global nodes of the input
  are not dictionary entries. The generated files are not checked in (they follow the generated input and its seed); `dictgen.py build
  <dataset>` rewrites them. A dictionary built by the dictionary ETL itself is not used here: that would make this layer depend on a
  second repository and on its build.
- **A dump (`DICT_DUMP=<dictionary_db_*.tar>`).** The tar the dictionary pipeline's export step writes: a directory-format `pg_dump` of
  schema `dict` (`--format=d --schema=dict --no-owner`) inside a tar. `dict-load` untars it and runs `pg_restore --format=d --schema=dict
  --clean --if-exists --no-owner --no-privileges`; schema `dict` is created first (a `--schema` archive does not carry it). Needed
  for `DATASET=dir`, which has no input to derive a dictionary from. A dump of a real dictionary is controlled-access metadata: see
  the rule below.

The service caches search results for an hour (`cache.duration-minutes`), so after changing the database by hand restart
`picsure-local-dict-api`. `compare.py` does not look at this layer; `qa.py` does (see QA).

## Dataset dir: an existing partition set

```
make up DATASET=dir HPDS_DIR=/data/partitions KEY_FILE=/data/encryption_key
make up DATASET=dir HPDS_DIR=... KEY_FILE=... LAYERS=hpds,api,dict DICT_DUMP=...   # with the API and a restored dictionary
make qa DATASET=dir HPDS_DIR=... KEY_FILE=... QA_MANIFEST="/data/manifest.json"    # the same variables again: qa reads the partitions
```

`DATASET=dir` runs no loader and compares nothing (`make compare` refuses it). It starts one HPDS, `picsure-local-hpds-dir` on :8084
(`STORE` is `dir` for the API), that mounts the inputs the way the BDC host does:

| host | container | |
|---|---|---|
| `KEY_FILE` | `/opt/local/hpds/encryption_key` | the path `Crypto` reads the key from |
| `HPDS_DIR` | `/opt/local/hpds/phenotypic/` (read-only) | `HPDS_DATA_DIRECTORY` points at it, so only the partition directories are scanned |
| `work/genomic/<GENOMIC>/all` | `/opt/local/hpds/all/` (read-write) | the genomic data, only with `GENOMIC=fixture` or `empty` (see QA scenarios) |

`HPDS_DIR` must hold only the partitions: one subdirectory each, with `allObservationsStore.javabin` and `columnMeta.javabin` (a loader's
`store/`, or a release dataset's `partitions/`). `serve.py check-dir` refuses anything else before a container starts: the parent of
`partitions/`, a dataset's `open/` or `static/`, one unpartitioned store, an empty or missing key. (HPDS treats every subdirectory as a
partition, so `open/` or `static/` would be served as two more, empty partitions.) Where HPDS reads the paths from, in this checkout:

- `PartitionedPhenotypicObservationStore` reads `HPDS_DATA_DIRECTORY` (default `/opt/local/hpds/`) and lists its subdirectories as the partitions; an `allObservationsStore.javabin` directly in it is the legacy unpartitioned layout, refused while the filter is on.
- `PhenotypeMetaStore` reads `<partition>/columnMeta.javabin`.
- `Crypto` hard-codes the key path `/opt/local/hpds/encryption_key`: the key is always mounted there, never under `HPDS_DATA_DIRECTORY`.

### QA scenarios: with and without genomic data

The authorized HPDS on the BDC staging stack runs profile `bdc-auth-dev`: `hpds.genomicProcessor.impl=localPatientDistributed` over
`HPDS_GENOMIC_DATA_DIRECTORY=/opt/local/hpds/all/`. `GenomicProcessorConfig` takes the first-level directories of that path as study groups
(at most 10) and the second-level ones as chromosomes (at most 50). With `all/` present and no directory in it HPDS dies at start
(`NullPointerException` in `GenomicProcessorPatientMergingParentImpl.initializePatientIds`). Without a Spring profile no genomic processor starts
at all, which is what the stack ran before and what a phenotype-only QA cannot tell from a broken genomic setup. `hpds-dir` (`DATASET=dir`) now
runs either, chosen by `GENOMIC`:

| `GENOMIC` | HPDS | expected QA |
|---|---|---|
| `off` | phenotype only, no genomic processor (the scenario that ran before) | `QA PASS [phenotype only]` |
| `fixture` (default) | the two BDC settings above and a genomic fixture laid out `all/<group>/<chromosome>/` | `QA PASS [phenotype + genomic]` |
| `empty` | the same settings, `all/` present with no directory in it | `QA FAIL [phenotype + genomic]` naming "genomic data directory has no dataset folders" |

```
make qa-scenarios                       # all three on the combined dataset (CSVLoader lane), one result line each, exit 1 unless as expected
make up DATASET=dir HPDS_DIR=... KEY_FILE=... GENOMIC=fixture GENOMIC_SPLIT_DIR=<dir of <partition>-allConcepts.csv>
make qa DATASET=dir HPDS_DIR=... KEY_FILE=... GENOMIC=fixture                          # the same GENOMIC; qa reads work/genomic/fixture/genomic.json
make up DATASET=dir ... GENOMIC=empty   # refused before HPDS starts: the pre-flight prints the FAIL (GENOMIC_PREFLIGHT=off starts HPDS and lets it die)
```

The two settings are written into `JAVA_OPTS` (`HPDS_GENOMIC_OPTS`) rather than activating the profile: the profile also names a dev dictionary
host and an S3 export bucket that do not exist locally. A unit test compares them with the profile file. `make qa` prints `scenario: ...` and the
verdict line carries the scenario (`QA PASS [phenotype only]` / `[phenotype + genomic]`); `qa.json` has `summary.scenario`. `make qa-scenarios`
reloads the dataset (split and CSVLoader, a few seconds) on every run, passes `--dataset` so the phenotype checks have their ground truth, and judges
each scenario from its `qa.json` and result line (`genomic.py expect`): `off` and `fixture` need exit 0 and their own PASS line, `empty` needs exit 1,
`QA FAIL [phenotype + genomic]` and a FAIL row of `genomic.preflight` or `genomic.startup` that names the missing dataset folders. An inconclusive run
or a crash is not "as expected".

The fixture (`genomic.py`) is built from the public 1000 Genomes test VCFs of the HPDS test resources (`open_access-1000Genomes.chr20`, `chr21`,
`chr4`) with the `SplitChromosomeVcfLoader` of `LOADER_REF`, one run per study group, so `work/genomic/fixture/all/<group>/<chromosome>/` is what the
loader writes. Samples are mapped to patients of the phenotype data (`GENOMIC_SPLIT_DIR`, the `<partition>-allConcepts.csv` files, read row by row for the patient of
every row HPDS loads; only the patient ids and a few concepts of the fixture's patients are kept, in `genomic.json`, so a release's partitions can be
read), dealt out over the partitions so that carriers lie in several, studies kept whole in a group, plus a few patients that are in no partition. The
expected answers are computed from the VCFs, not from the loader's output; records with the same spec are one variant, as the loader makes them.
The split directory only builds the fixture: it does not change any phenotype check of the run. Without `GENOMIC_SPLIT_DIR` the patients are made up,
which patients each consent grants is not known, and the checks that need it (counts per consent, phenotype + genomic, scoping per partition) are
SKIPped with that reason; pre-flight, startup, config, the patient count, variant counts as upper bounds, the info checks, the unknown consent and
no consents are still PASS or FAIL, so a run where everything works exits 0. HPDS runs as root and writes its variant index caches into the mounted
genomic directory (mounted read-write only with genomic data; `GENOMIC=off` mounts nothing useful, read-only), so the fixture is rebuilt at every
`make up`. `work/genomic` holds patient ids of the run: `make clean` and `make clean-data` remove it (through a container of the local HPDS image
when the host cannot remove root-owned files, so no registry is needed), and nothing outside `work/genomic` is ever removed by `genomic.py`.

`qa.py` checks, with `--genomic-dir`, in the checks `genomic.*`: the directory (pre-flight; the cause is named when it has no study group or
chromosome folders), an HPDS that is not up on it (FAIL with the cause read from its log, not an inconclusive setup), that the container runs the BDC
settings, the genomic patient count HPDS logs, variant-spec and variant-info queries (alone, per consent, and one zygosity), a phenotype + genomic
query, and consent scoping: genomic data is not partitioned by consent, so a genomic filter alone is answered for the patients of the caller's
consents only, an unknown consent gets 0 and no consents get no count. The scoping rows use, per partition, a variant with carriers inside it and in
another partition, so the expected count is not zero and a cross-partition leak changes the answer (a patient in no partition is covered by the
all-consents row). An HPDS that is not up on a genomic directory is a FAIL when the pre-flight fails, the log names the cause, or its container
exited. The patient count is read from the whole container log (`docker logs`, no tail).

For a headless runner that serves a real release with `DATASET=dir`: `GENOMIC=fixture` is the default, so `make up` builds the fixture (it needs
Java and Maven for the loader jar, like the other jars) and mounts it. Pass `GENOMIC_SPLIT_DIR` (a directory of the release's
`<partition>-allConcepts.csv`) to map the fixture's samples to real patient ids and get exact counts; the ids stay in `work/` on that host. Without it
the counts per consent are SKIPped (exit 0 when everything else works). `GENOMIC=off` keeps the old phenotype-only run. Open points: the settings test
compares the profile file of this checkout, not the tree of `SERVICES_REF`; a patient in two studies is placed in the group of its first partition
only; the variant checks send a few hundred small queries.

### Open mode

`OPEN=1` (the default) also starts `picsure-local-hpds-open` on :8085: the same partitions with `-Dhpds.requireAuthorizationFilter=false`.
A query without consents sees every partition, one with consents only those. With the `api` layer the query-service's open backend
(`HPDS_OPEN_URL`) is this HPDS; without it, the same HPDS as the auth backend. Memory doubles (a second `-Xmx1g` JVM): `OPEN=0` skips it.

The open HPDS can have its own `columnMeta.javabin` per partition (what a release's `open/partitions/<p>/columnMeta.javabin` is: the metadata
without the stigmatized variables):

- `OPEN_COLUMNMETA_DIR=<dir>` with `<dir>/<partition>/columnMeta.javabin`: `serve.py open-root` builds `work/<dataset>/open-root`, a copy of the
  served partitions (hard links; the source is never written, and a store is never copied, so `work/` must be on the file system of the
  partitions) whose `columnMeta.javabin` is the override. Every partition needs one: a partition without an override would be served
  with its full columnMeta, so the run stops; so it does for an override of a partition the store lacks, or an empty one. Nothing else
  changes in the open HPDS. In a lane run whose served lane has no store (for example `FLOWS=csvloader-split`) the open HPDS is not started
  and the run goes on; with `DATASET=dir` that is an error.
- A synthetic dataset (not `dir`) gets one by default: `STIGVARS` (default `fixtures/qa/stigvars.txt`) lists concept paths, and
  `RemoveConceptFromMetadata` (the release flow's tool, from the loader jars directory, run in a container) removes them from each
  partition's columnMeta into `work/<dataset>/open-columnmeta`. It removes exact paths only, and `qa.py --stigvars` checks the same list by exact path. `STIGVARS=` (empty) skips it.
- With neither, the open HPDS serves the same files with the filter off.

An override is only valid for the store it was made from: a `columnMeta.javabin` holds offsets into that partition's
`allObservationsStore.javabin`. After a reload, rebuild the overrides; with stale ones the open HPDS answers an empty body to queries on a
concept, which `make qa` reports as FAIL in `open.consents` / `open.none`.

## QA battery

`make qa` runs `qa.py` against the stack that is up (stdlib only, counts only: it prints and writes counts, concept paths of a failing check and
nothing patient-level). It talks to the HPDS of `STORE` (or `hpds-dir`) and, when they are running, to the open-mode HPDS, the query-service
and the dictionary. `make run LAYERS=hpds,api,dict` followed by `make qa` is the synthetic gate.

```
make run LAYERS=hpds,api,dict && make qa                  # synthetic combined dataset: everything on
make qa QA_REQUIRE=open,api,dict                          # a missing layer is a setup problem (exit 2), not a skipped check
make qa QA_MANIFEST="work/combined/split/split_manifest.json"   # more sources of truth (a flow manifest.json, ingest-summary.json)
make qa QA_ARGS="--sample 20 --max-partitions 50 --verbose"
python3 qa.py --help
```

| check | what must hold |
|---|---|
| `partition.patients` | `COUNT` with `userConsents=[<partition>]` and no clause equals the source of truth: the dataset's ground truth (synthetic), the manifest(s) (`patients`), else the partition's `\_consents\` concept in `columnMeta.csv`. No source at all is INCONCLUSIVE |
| `partition.observations` | the sum of `observationCount` in the partition's `columnMeta.csv` equals the manifest / truth observation total |
| `concept.sample` | for a sample of concepts per partition (`--sample`, seeded): the `REQUIRED` count equals `columnMeta.csv` patientCount and the observation count (`OBSERVATION_CROSS_COUNT`, one concept per query) equals observationCount |
| `union` | two consents count a patient once: exact against the truth (pairs that share a patient are preferred), else `max <= union <= sum`, order independence, and a consent listed twice counts once; all partitions together |
| `global.served` | with a global partition (kind `global` in a `--manifest`, else a partition named `global`): it is not a study partition and is left out of the per-consent and per-study checks (only its observation total is compared). The open HPDS counts more than 0 patients for its sampled concepts with no consents (SKIP without `--open`); the authorized HPDS counts 0 for a concept only the global partition holds when asked with the consents of every study partition (SKIP, with the reason, when every concept of the global partition is also in a study partition, as in `globals=both`, or when no concept is sampled with `--sample 0`; INCONCLUSIVE when a study partition has no `columnMeta.csv`, since which concepts only the global partition holds is then not known, and when the global partition has none). The release manifest's `source.globals` (`split`, `global`, `both`) is named as `globals=<mode>` in the detail of `partition.patients` and `global.served`; it changes no expectation, so a study count that differs from the manifest in mode `global` is still a FAIL |
| `consent.missing` | a consent with no partition counts 0, alone, with a concept, and next to a real one |
| `consent.none` | a query without consents is refused: an answer that is not a count (today an empty 200, see Layer api). A connection error, a 5xx and "Resource is locked" are not a refusal and fail |
| `open.none` / `open.consents` | open HPDS: no consents = every partition (equal to the auth HPDS asked for all partitions; with a global partition the open HPDS also serves it, so a bound instead: at least the larger of the study partitions' patients and the global partition's, at most their sum, and the detail says how many patients the open HPDS counts that the study partitions do not), with consents = those partitions, unknown consent = 0 |
| `stigvar.open` / `stigvar.control` | with `--stigvars`: the listed paths are matched exactly (a path below a listed path is not listed). Of the listed paths that are in the partitions' columnMeta, `--stigvar-limit` (default 200, 0 = all) are checked, at least one per study and the rest evenly spaced in the sorted paths, so max(limit, number of studies) in all: none may be in the open HPDS's search, and the open HPDS must count nothing on it. Listed paths in no examined partition are one informational `coverage` row, which also says how many were listed, in the partitions and checked and is printed in the console summary (a sampled PASS says "C checked" of M), so a list of 100k+ entries costs a bounded number of calls. The authorized HPDS is the control: a checked path missing there is INCONCLUSIVE, and so is a list with nothing to check. The global partition is not checked for listed variables. With an open HPDS and no list the check is INCONCLUSIVE too (`QA_REQUIRE=stigvar` also fails a run with no open HPDS) |
| `api.same` | the query-service answers the HPDS's answer (counts, observation counts, no consents, unknown consent), query by query |
| `dict.consent` | with a consent filter the dictionary returns concepts of that consent's study (or of a global / harmonized dataset), never of another study |
| `dict.study_paths` | the study's dictionary concept paths are a subset of the union of its partitions' columnMeta paths. A release's `partitions/` has no `columnMeta.csv`: give `QA_COLUMNMETA_ROOT=<dir>/<partition>/columnMeta.csv` (for example a local copy of the pheno-load flow prefix), else this and `dict.global_paths` are INCONCLUSIVE |
| `dict.global_paths` | global / harmonized dictionary paths (a dataset that is not a study) against the same partitions, reported on their own |
| `dict.unknown` | a consent with no partition returns no concepts |
| `genomic.*` | with `--genomic-dir` (the HPDS runs the BDC genomic settings): `preflight`, `startup`, `config`, `patients`, `variant_spec`, `info`, `combined`, `consent` (see QA scenarios) |

A consent filter in the dictionary works on the study (dataset), not on the consent group: `phs000000.c1` returns every concept of `phs000000`,
and the harmonized datasets linked to it. Exit status of `qa.py`: 0 = every check PASS (a check with no target is SKIP), 1 = any FAIL, 2 = no FAIL but a
check INCONCLUSIVE or the setup unusable (HPDS down, no partitions). `make qa` stops with make's own status 2 for any non-zero exit of
the script: read the last line (`QA PASS|FAIL|INCONCLUSIVE`) or `summary.exit` in `qa.json`, or run `python3 qa.py` directly for the codes. Reports: `work/report/qa/qa.json` and `qa.tsv` (every case: status, expected, got, detail).
`OBSERVATION_CROSS_COUNT` with several concepts in `select` loses entries under concurrency in HPDS (a `TreeMap` written from a parallel
stream), which is why the battery asks one concept at a time. The query-service's open path (`/hpds/open/...`) obfuscates small counts and
is not compared; the battery queries the open HPDS directly.

### Real data stays in the boundary

Real datasets are controlled-access. Run `DATASET=dir` and `make qa` on a host inside the boundary, with the key and the partitions on that
host, and keep the results there: the reports hold counts and concept paths only, but a running HPDS or query-service answers any
query. **Do not port-forward, tunnel or publish (`BIND_ADDRESS=0.0.0.0`) the ports of a stack that serves real data to a laptop** (the Makefile refuses `DATASET=dir` and `DICT_DUMP` unless `BIND_ADDRESS=127.0.0.1`, and the dump is extracted into a tmpfs of `dict-load`), and do not
restore a real dictionary dump anywhere outside the boundary. The stack's defaults bind loopback only. On a laptop, only the synthetic datasets.

## Datasets

All data is synthetic (except what you serve yourself with `DATASET=dir`). Accessions are the placeholders `phs000000`, `phs999999` and `phs123456` (plus the bare consent
`tutorial-biolincc_test`); patient numbers are made up.

| dataset | kind | what | lanes |
|---|---|---|---|
| `mini` | split | the five partitions of the hpds-pheno-load smoke test, copied to `fixtures/mini/` (see its README). `phs000000.c2` is deliberately malformed: a 3-column row, a 6-column row, a blank line and an invalid byte | 2, 3 |
| `1000g` | split | the four partitions of the HPDS test resources (`services/pic-sure-hpds/service/src/test/resources/phenotypic`), linked, not copied | 2, 3 |
| `edge` | split | `fixtures/edge/split`: one partition with the rows the loaders read differently (whitespace-only nval, NaN and Infinity; see Known differences). Part of `DATASET=all` | 2, 3 |
| `combined` | combined | `gen-combined.py`: 80 patients, about 1000 rows in the shape of `allConcepts_data_analyzer.csv` | 1, 2, 3 |
| `synth` | combined | `gen-synth.py --patients N --concepts M --seed S` (defaults 200, 12, 7; `make compare DATASET=synth PATIENTS=2000 CONCEPTS=40 SEED=3`) | 1, 2, 3 |
| `dir` | existing | `HPDS_DIR`, `KEY_FILE`: any partition root, served as is (see Dataset dir); no lanes, no comparison | none |

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
| 1 | `picsureloader-combined` | PICSURELoader reads the combined file and routes it by consent itself (`-Dpicsure.loader.input=<file> -Dpicsure.loader.partitionBy=consent`); needs a T25b-capable jar, see below |
| 2 | `csvloader-split` | splitter, then `LC_ALL=C sort -t, -k2` per partition, then one CSVLoader JVM per partition |
| 3 | `picsureloader-split` | splitter, then PICSURELoader on all partitions (unsorted, `failFast=false`) |

The CSVLoader of lane 2 is the one of `LOADER_REF` (default `SERVICES_REF`: the deployed loader); PICSURELoader (lanes 1 and 3) is built from
this checkout (see What is tested).

The splitter is `split-allconcepts-by-consent` of pic-sure-hpds-etl at commit `6416d91` (`SPLITTER_REF`), extracted with `git archive`
into `work/src/hpds-etl` and built there; the checkout is never written to. Options: `--globals=by-patient --partition-naming=suffix
--timestamps=convert-iso --zero-timestamps=blank --bad-rows=quarantine --max-reject-fraction=0.05 --force`. If pic-sure-hpds-etl is not
next to this checkout, pass `SPLITTER_REPO=<path>` or `SPLITTER_JAR=<hpds-etl.jar>`.

Lane 1 needs a PICSURELoader that knows the combined-file options (`-Dpicsure.loader.input`, `-Dpicsure.loader.partitionBy`; T25b).
Only a run with such a jar exercises lane 1 at all; with any other jar the lane is skipped and nothing was tested for it. The stack
finds out in two steps. A static check: the property name `picsure.loader.partitionBy` must be in the jar; if not, the lane is skipped. Then a probe: the
jar runs (`-Xmx512m`) on a tiny generated combined file into a temporary base under `work/` and must exit 0 and write a store per consent.
A jar that has the option and fails the probe (broken jar, crash, timeout, wrong output) is not skipped: lane 1 is FAILED, with the
probe's exit code and the tail of its log in the reason, in every mode but `LANE1=off`, and also when `FLOWS=` did not ask for lane 1 (the report then has a `load:probe` mismatch instead of an `n/a` row). `LANE1` says what to do about a jar without the option:

| `LANE1` | capable jar | other jar |
|---|---|---|
| `auto` (default) | lane 1 runs | lane 1 is skipped (`lane1 skipped: PICSURELoader without combined input`), reported, not a failure |
| `require` | lane 1 runs | lane 1 fails (the run fails): use it with a jar that must have T25b |
| `off` | skipped (`LANE1=off`) | skipped |

Split datasets (`mini`, `1000g`, `edge`) have no combined file and run lanes 2 and 3 only.

For lane 2 the stack applies the runner's check before sorting (`pipelines/hpds-pheno-load/entrypoint.sh`): the non-empty lines of a
partition must equal its CSV records, because `sort` would tear a quoted line break apart. A partition that fails it is a failed
partition, not a silent one.

Both loaders write next to their input, so a lane always works on copies or links under `work/`.

## Make targets

| target | what |
|---|---|
| `make jars` | the jars of the served services from `SERVICES_REF` (the HPDS service; with `api` in `LAYERS` the query-service and operations-service; with `dict` the dictionary), extracted and built in `work/src/services-<sha>/`; for a synthetic dataset also the CSVLoader of that ref and PICSURELoader built from this checkout (`services/pic-sure-hpds/docker/pic-sure-hpds-etl/`, which git ignores); and the splitter, built only when a selected dataset needs splitting (`combined`, `synth`). See What is tested |
| `make split DATASET=` | generate or link the input, run the splitter |
| `make load DATASET= [FLOWS=]` | run the flows in parallel (`make -j`); each writes `work/<dataset>/<flow>/load.json` (wall time, exit code, partitions loaded and failed, store size, the PICSURELoader's per-partition counts) and `load.log`. `status` is `ok` only when the loader exited 0, loaded something and no partition failed. The flows asked for are recorded in `work/<dataset>/requested-flows.json` |
| `make up [LAYERS=hpds,api] [STORE=] DATASET=` | build the HPDS image, (re)start the lanes' containers on that dataset's stores and wait for `/actuator/health`; with `api`, build the two API images, start MySQL, flyway, operations-service and query-service pointed at `STORE`'s HPDS and wait for them |
| `make compare DATASET=mini\|1000g\|combined\|synth\|edge\|all` | `jars`, then `split`, `load`, `up` and `compare.py run` (with `api` in `LAYERS`: `--via=api --store $STORE`) per dataset, then the report. Exit status 1 on an unexplained mismatch (see The result line) |
| `make run` | `make compare DATASET=combined`, then the URLs and the report path |
| `make qa` | `qa.py` against the stack that is up (see QA battery): exit 0 PASS, 1 FAIL, 2 inconclusive; reports in `work/report/qa/` |
| `make qa-scenarios` | the three QA scenarios (`GENOMIC=off`, `fixture`, `empty`) on `SCENARIO_DATASET` (default `combined`, loaded by the CSVLoader of the ref), each started and checked on its own; exit 1 unless off and fixture PASS and empty FAILs with its cause. Reports in `work/report/qa-<scenario>/` |
| `make test` | `python3 -m unittest discover -s tests` |
| `make down` / `restart` | stop and remove the containers of every layer (`restart` = down + up, with the same `LAYERS` and `STORE`) |
| `make clean` / `clean-data` | remove `work/` / remove the datasets and the report but keep the splitter build and the image. Both check first (before stopping the containers) that the target resolves, symlinks followed, to this checkout's `tools/local-stack/work` and is not itself a symlink; otherwise they say why and delete nothing. The Python helpers check the same before every delete |

`DATASET=all` (mini, 1000g, combined, synth, edge) leaves the stack serving the last dataset (`edge`). `make up DATASET=mini` switches it. Python:
`make test` (or `python3 -m unittest discover tools/local-stack/tests`).

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
  query without `userConsents` answers an empty body, not a count (the compose file sets `-Dhpds.requireAuthorizationFilter=true` explicitly).
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

(With `--via=api` the report has one more section, `Through the API`, and `compare.json` an `api` entry per dataset and in the summary:
store, URL, health, queries asked and answered, differences. See Layer api.)

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

A difference that matches a known difference (below) is **explained**; everything else is **unexplained** and makes the exit status 1.
A rule explains only the kinds of mismatch it lists, for the concepts and partitions the run found evidence for, and only in the
CSVLoader lane (or between it and another lane); it never explains an HTTP error. The report lists the unexplained mismatches first,
then the explained ones with the known difference that covers them, then each known difference with the evidence found in the run.

Also a failure (an unexplained `load` mismatch, not a pass by omission):

- a flow the run asked for with no `load.json`, with a status other than `ok`, or with any failed partition (whatever its status);
- a query that is not answered with a count;
- a dataset that asked no query at all, or a loaded lane that asked none.

### The result line

| result | meaning |
|---|---|
| `PASS` | every flow that applies to every dataset ran and passed, and no lane was skipped |
| `PASS (lane1 skipped: <why>)` | as above, but lane 1 was skipped (a jar without T25b, or `LANE1=off`): lane 1 was not tested |
| `PASS (partial: <flows> not run)` | `FLOWS=` asked for fewer flows than apply, a debug run. Each flow not run is a `WARN not run` line in `compare.md` and on the console |
| `FAIL: ...` | an unexplained mismatch, unanswered queries or nothing compared |

The parts combine (`PASS (partial: csvloader-split not run; lane1 skipped: ...)`). Only the default, all applicable flows, can print a
plain `PASS`. A lane 1 that cannot run here is not counted as "not run" when `FLOWS=` leaves it out. `make compare DATASET=combined
FLOWS=picsureloader-split` is the example of a partial run.

## Known differences

These are written from evidence found by running the stack (the report repeats them with the counts of the run).

- **`hpds-min-above-max`: a range minimum above every value of a numeric concept: `csvloader-partition-names` answers an empty 200, an HPDS
  built from `feature-harmonization` answers 0.** The HPDS of `csvloader-partition-names` has the old `PhenoCube.seekForMinIndex`: when the minimum
  is above every value, `Arrays.binarySearch` returns `-(length) - 1`, the index becomes `length + 1` and the read is out of bounds, which the
  service turns into an empty body. It does not matter whether `max` is set (the minimum is looked up first), and an empty cube fails the same way.
  `PhenoCube.java` on `feature-harmonization` clamps the index (`minSearchIndex = Math.min(minSearchIndex, sortedByValue.length - 1)`), so
  `HPDS_REF=feature-harmonization` answers 0 and the rule is not needed there. The service is not patched here: the difference is reported as a
  finding for the service, and the comparison names it. With the default `SERVICES_REF` the battery's "above the maximum" queries come back with an
  empty body in every lane (48 mismatches on the `combined` dataset). The rule explains a mismatch only when all of this holds: the expected count
  is 0 (from the truth); the query is a numeric range filter (`min` set, with or without `max`); its `min` is above the maximum of the concept in
  every named partition that has values for it (or the concept has no values there), by the truth; the concept is numeric in them; every
  lane that differs answered an empty 200 body; and every running HPDS is at the commit of the deployed ref (the evidence names it), so an HPDS of
  another commit that answers an empty body is a finding, not this difference. A wrong count, an error status, a `min` that is not above the maximum (the bounds are inclusive)
  and a concept that is not numeric stay unexplained. An explained empty body counts as answered.

- **`fffd-in-text`: CSVLoader keeps U+FFFD in a text value, PICSURELoader removes it.** `mini` partition `phs000000.c2` has `bad<0xFF>byte`
  as a value. Java reads the byte as U+FFFD. CSVLoader stores `bad<U+FFFD>byte` (width 10), PICSURELoader `badbyte` (width 7), so a
  query for `badbyte` finds the patient only in lane 3, and the category lists differ. The truth is the PICSURELoader result. This is
  the "malformed partition" of `mini`: it loads in both lanes, and what differs is explained. The rule is narrow: it explains the
  category list and column width of that concept, and a value filter whose values include one that differs only by U+FFFD. It does not
  explain a `REQUIRED` count, an observation or patient count, a min or max, or an error.
- **`short-rows`: a row with fewer than four columns is skipped by both loaders** (mini's 3-column row). The PICSURELoader records it as
  `MALFORMED_RECORD`, the CSVLoader only logs it. Both stores hold the same data. Six columns, a blank line and the invalid byte are
  tolerated by both.
- **`rollup-last-node`: a last path node equal to the text value is dropped** (`\a\b\Yes\` with value `Yes` becomes `\a\b\`). The
  PICSURELoader always does it; the CSVLoader only with `-Dhpds.csvloader.rollUpVarNames=true` (off by default since the opt-in change on
  `csvloader-partition-names`). Lane 2 starts the CSVLoader with it on (`CSVLOADER_ROLLUP=true`, recorded in its `load.json`), so the
  lanes stay comparable; `CSVLOADER_ROLLUP=false` shows the CSVLoader's default, where concepts that end in a value differ from the truth.
  The truth applies the rule; it is listed because it surprises people querying by full path. `combined` and `synth` contain such concepts.
- **`csvloader-numeric-text`: a number in the text column stays text in the CSVLoader.** With an empty nval, the PICSURELoader reads a
  text value that parses as a finite number as a numeric observation (the truth follows it); the CSVLoader keeps it as a categorical
  value. The concept is then categorical in the CSVLoader lane, or one of its two row types is dropped, so counts, min, max, categories
  and value filters of such a concept differ. `combined` and `synth` write numbers in the text column.
- **`no-value-rows`: a row with neither value stores nothing** in both. The PICSURELoader reports it as `MISSING_VALUE`.
- **`csvloader-empty-row-first`: the CSVLoader types a concept by its first row.** After the sort, an empty-valued row of a numeric
  concept comes first, the concept becomes categorical and all its numeric values are dropped (0 observations); a concept with only
  empty rows is stored with 0 observations. Tried on a three-row file: CSVLoader 0 observations, PICSURELoader 2. The datasets avoid
  empty rows on numeric concepts so the lanes stay comparable. The comparison explains it only for a concept whose CSVLoader
  columnMeta is categorical with 0 observations.
- **What the three CSVLoader rules may explain** is narrow. None of them can drop a concept (the CSVLoader creates the concept before it
  looks at the value), so none explains a concept missing from the CSVLoader's columnMeta. A concept the truth lacks but the CSVLoader
  has, and a presence-only difference between lanes, are explained only for `csvloader-empty-row-first` and
  `csvloader-nonfinite-numbers`, and a presence difference only when the CSVLoader is the lane that has the concept. NaN and Infinity
  are explained only for a concept whose CSVLoader columnMeta is numeric or has a NaN or Infinity min or max.
- **`csvloader-whitespace-nval`: an nval of only whitespace.** The CSVLoader tests the raw cell, so the row is numeric, trims to
  nothing and is dropped even when it has a text value (its concept is typed numeric by that row, so later text is dropped too). The
  PICSURELoader trims first, so the nval is empty and the text value is kept (a number when it parses). With no text both drop the row.
  Checked with both jars on `fixtures/edge`. The splitter quarantines such a row as `BAD_NVAL`, so `combined` and `synth` cannot carry
  it to the loaders. The truth follows the PICSURELoader.
- **`csvloader-nonfinite-numbers`: NaN and Infinity.** The CSVLoader stores them as numbers (`Double.parseDouble`), in nval or in the
  text value; a concept with them gets NaN as min and max. The PICSURELoader keeps finite numbers only: NaN in nval falls back to the
  text value, NaN in the text value is the categorical value `NaN`. Checked on `fixtures/edge`. An nval of `NaN` or an infinity with no
  text value is dropped by the PICSURELoader as `NON_FINITE_NUMBER`: recorded and counted, but not crash-class, so it never fails a
  partition (the CSVLoader stores the value); `fixtures/edge` has no such row.
- **`splitter-quarantine`: the splitter sets rows aside**: a header row in the middle, `2402L` as nval, and rows of patients without a
  consent. The truth routes the rows independently and agrees with `split_manifest.json`. With a T25b jar, lane 1 does this routing inside the
  PICSURELoader and agrees with the truth too (checked on `combined` and `synth` with `LANE1=require`) (it reports `MISSING_VALUE` and `NUMERIC_PARSE_ERROR` rows instead of quarantining).
- A CSVLoader without the hyphen fix (`CSVLoader.partitionNameFor`, branch `csvloader-partition-names`) names `tutorial-biolincc_test`
  `tutorial`: the lane shows 4 of 5 partitions loaded and the comparison reports the missing partition.
- CSVLoader needs input sorted by concept (16 cached cubes) and loses data silently otherwise; lane 2 sorts, the PICSURELoader does not
  need it.

## Files

| file | what |
|---|---|
| `Makefile`, `docker-compose.yml`, `.env.defaults` | the stack; ports, hosts and images in `.env.defaults`, per-machine overrides in `local.env` (ignored by git) |
| `api/sql/` | the one local migration the operations-service's own set lacks (`version` column) |
| `dict/load.sh` | what the `dict-load` container runs: the synthetic SQL, or `pg_restore` of `DICT_DUMP` |
| `dictgen.py` | the synthetic dictionary (schema + data SQL) of a dataset, from its ground truth |
| `serve.py` | `DATASET=dir` checks and the open-mode partition root |
| `qa.py` | the headless QA battery |
| `genomic.py` | the genomic fixture (public VCFs, `SplitChromosomeVcfLoader`), its expected answers (`genomic.json`) and the check of a genomic directory |
| `fixtures/qa/stigvars.txt` | the stigmatized-variable list the synthetic open columnMeta is built without |
| `lane.py` | split step and loader lanes |
| `services_ref.py` | resolves `SERVICES_REF` (and the per-service refs), extracts and builds each commit, records and reports the provenance |
| `compare.py` | queries (direct, and with `--via=api` through the query-service), columnMeta checks, report |
| `pheno.py` | the ground truth |
| `datagen.py`, `gen-combined.py`, `gen-synth.py` | synthetic input |
| `fixtures/mini/` | the mini dataset and its key (test only) |
| `fixtures/edge/` | the edge dataset: rows the two loaders read differently |
| `tests/` | unit tests |
| `work/` | everything built or loaded (ignored by git) |
