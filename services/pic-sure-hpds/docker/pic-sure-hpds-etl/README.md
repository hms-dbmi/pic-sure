HPDS ETL Process
================

*I2B2 Oracle SQL

To load your I2B2 registry data, fill out the hpds/sql.properties file with your connection details and create an encryption key in the hpds/encryption_key file.

The specific requirements for the sql.properties file will depend on your environment. The encryption_key file must have only 32 hexadecimal characters and no other content.

Build the current ETL artifacts from the repository root before running a loader:
```
mvn -pl services/pic-sure-hpds/etl -am package -DskipTests
```

Once this is done, run the loader. The compose files build `pic-sure-hpds-etl:local`
from this checkout rather than pulling a historical image:
```
docker compose -f docker-compose-sql-loader.yml up --build
```
`--build` only re-copies the staged loader jars into the image; it does not compile
them. After any change to ETL Java source, re-run the `mvn package` command above
before `--build`, or the image will carry stale loader code.

Every checkout builds to the same `pic-sure-hpds-etl:local` tag. Set
`COMPOSE_PROJECT_NAME` if you need containers from two checkouts to coexist.

The loaders run as the non-root `etl` user (UID 1000). Docker Desktop remaps
bind-mount ownership, so this needs nothing from you on macOS, or on Windows when the
checkout sits on a Windows drive. A checkout inside the WSL2 Linux filesystem is real
ext4 and follows the Linux rules below.

On Linux, every mounted directory must be writable by the UID the container runs as.
`hpds/`, `hpds/all/` and `vcfLoad/` are in the checkout so they carry your ownership;
if you point a loader at a directory Docker has to create, the daemon makes it
`root:root` and the loader cannot write to it. Where the UIDs do not line up, pass
your own:
```
ETL_UID=$(id -u) ETL_GID=$(id -g) docker compose -f docker-compose-sql-loader.yml up --build
```

The logs will show all concepts as they are loaded and some other information. Once this process exits, you should have two new files in the hpds folder:
```
columnMeta.javabin
allObservationsStore.javabin
```
The first holds all of the metadata for all concepts. The second holds the actual concept data. These files are not readable using anything except HPDS.

To make these files available to your HPDS container, volume map them into the following container path:
```
/opt/local/phenocube
```
You will of course need to unlock the HPDS instance once you have done that, which is outside the scope of this `README.md` file.

It is a good idea to validate the following once you have your data hosted in an HPDS instance:

Number of concepts vs expected number of concepts.
Number of patients vs expected number of patients.
Total number of facts.

These values are dumped into the log of the loading process at the end, immediately preceded with statistics for each concept.

PICSURELoader (phenotype data, partitioned by consent)
------------------------------------------------------

`PICSURELoader` loads `*allConcepts.csv` files into one HPDS phenotype store per consent. It is a drop-in for `CSVLoader`
(same output files, same `encryption_key` file) with these differences:

- **The base directory is a required argument** (`LOADER_ARGS`, or the first argument of `java -jar`). There is no
  `/opt/local/hpds/` default. It holds `encryption_key` and the `<partition>-allConcepts.csv` files.
- The input does **not** have to be sorted by concept. Nothing is sorted or copied; evicted concepts are spooled to disk and merged
  once at the end.
- The partition is the file name without `-allConcepts.csv`, so `tutorial-biolincc_camp-allConcepts.csv` becomes the partition
  `tutorial-biolincc_camp` (names must match `^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$`; duplicates, also ignoring case, are refused).
  `CSVLoader` cuts the name at the first `-`.
- A missing or wrong-length `encryption_key` (not 16, 24 or 32 bytes) stops the run before any work.
- Each partition is written to `<directory of the input file>/<partition>/` as `allObservationsStore.javabin`, `columnMeta.javabin` and
  `columnMeta.csv` (the same 11 columns `CSVLoader` writes), plus `failures.jsonl` (rows that were dropped or noted) and
  `concept_rejections.jsonl`. `<base>/ingest-summary.json` has the counts per partition: rows read, rejected by reason, concepts,
  patients, spooled partials, timings, and whether every row is accounted for.
- **Nothing is lost quietly.** The run exits 0 only if every partition loaded completely. A partition fails (its three store files are
  deleted and the run ends non-zero; by default the remaining partitions are not run) on: an exception, a concept over the optional
  `maxObservationsPerConcept` limit, a concept that could not be written, rows that do not add up, nothing to store, more than
  `maxRejectedRows` (default 0) rows with a patient id or number that `CSVLoader` would have stopped on, or more than
  `maxTimestampFailures` (default 0) timestamps that cannot be parsed. Rows `CSVLoader` skips silently (fewer than 4 columns, no value)
  are recorded but do not fail a partition.
- A concept is numeric or categorical by all its rows, not by the first one. Null markers (`NA`, `None`, `NaN`, `N/A`, `null`, `\N`,
  empty) are dropped from a numeric concept and kept as categories of a categorical one; a concept of only null markers is a
  categorical concept of them. Numbers in a categorical concept keep the text they were written as (`007` stays `007`).
- **Re-running in the same base directory is refused**: a partition directory that already holds a store stops the run (exit 2) before
  any work. Use a fresh base directory, or `-Dpicsure.loader.overwrite=true` to replace the stores.

Tuning is by system properties, passed in `JAVA_OPTS` (see `LoaderConfig` for the full list):

| Property | Default | |
|---|---|---|
| `picsure.loader.spoolDir` | `<base>/.picsure-spool` | spool root, one subdirectory per partition, removed afterwards; use fast local disk |
| `picsure.loader.maxObservationsPerConcept` | `0` (no limit) | a concept that reaches a non-zero limit fails the partition (kept null markers count) |
| `picsure.loader.maxRejectedRows` | `0` | rows dropped for a patient id or number `CSVLoader` cannot parse that a partition tolerates |
| `picsure.loader.maxTimestampFailures` | `0` | unparseable timestamps that a partition tolerates (the row is kept without one) |
| `picsure.loader.dedup` | `false` | `true` drops exact duplicate rows (`CSVLoader` keeps them) |
| `picsure.loader.failFast` | `true` | `false` loads the other partitions after a failure |
| `picsure.loader.overwrite` | `false` | `true` replaces a store that is already in a partition directory |
| `picsure.loader.chunkConcurrency` | min(cores, `storeCacheSize`/2), at least 1 | chunks of a file above 500 MB parsed at once |
| `picsure.loader.batchSize`, `storeCacheSize`, `finalizationConcurrency`, `finalizationChunkSize` | 10000, 16, 12, 1000 | memory and speed |

Run it with `docker compose -f docker-compose-picsure-loader.yml up --build` after `mvn -pl services/pic-sure-hpds/etl -am package
-DskipTests`.

**Sizing the heap.** Set `HEAPSIZE` (MB) from the largest concept, not the largest file: finalization merges, sorts, serializes and
encrypts one concept at a time in memory, about 400 bytes per observation for numbers, and more for categorical concepts with long free
text (a long string costs its length twice). A heap budget (half of the max heap) keeps several large concepts from being finalized
together, but a single concept bigger than the budget still has to fit. A concept of 100 million numeric observations needs roughly
40 GB. The summary reports `spooledPartials` per partition: a large number means concepts are evicted from the cache of 16 concepts
and written back and forth; raise `storeCacheSize` if there is heap to spare.
