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
(same `LOADER_ARGS` base directory, same `encryption_key` file, same output files) with these differences:

- The input does **not** have to be sorted by concept. Nothing is sorted or copied; evicted concepts are spooled to disk and merged
  once at the end.
- The partition is the file name without `-allConcepts.csv`, so `tutorial-biolincc_camp-allConcepts.csv` becomes the partition
  `tutorial-biolincc_camp` (names must match `^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$`; duplicates, also ignoring case, are refused).
  `CSVLoader` cuts the name at the first `-`.
- A missing or wrong-length `encryption_key` (not 16, 24 or 32 bytes) stops the run before any work.
- Each partition is written to `<directory of the input file>/<partition>/` as `allObservationsStore.javabin`, `columnMeta.javabin` and
  `columnMeta.csv` (the same 11 columns `CSVLoader` writes), plus `failures.jsonl` (rows that were dropped or noted) and
  `concept_rejections.jsonl`. `<base>/ingest-summary.json` has the counts per partition: rows read, rejected by reason, concepts,
  patients, timings, and whether every row is accounted for.
- The run exits 0 only if every partition loaded completely. A partition that fails (an exception, a concept over the optional
  `maxObservationsPerConcept` limit, a concept that could not be written, rows that do not add up, nothing to store) has its three store
  files deleted, and the run ends non-zero. By default the remaining partitions are not run.

Tuning is by system properties, passed in `JAVA_OPTS` (see `LoaderConfig` for the full list):

| Property | Default | |
|---|---|---|
| `picsure.loader.spoolDir` | `<base>/.picsure-spool` | spool root, one subdirectory per partition, removed afterwards; use fast local disk |
| `picsure.loader.maxObservationsPerConcept` | `0` (no limit) | a concept that reaches a non-zero limit fails the partition |
| `picsure.loader.dedup` | `false` | `true` drops exact duplicate rows (`CSVLoader` keeps them) |
| `picsure.loader.failFast` | `true` | `false` loads the other partitions after a failure |
| `picsure.loader.overwrite` | `false` | `true` replaces a store that is already in a partition directory |
| `picsure.loader.batchSize`, `storeCacheSize`, `finalizationConcurrency`, `finalizationChunkSize` | 10000, 16, 12, 1000 | memory and speed |

Run it with `docker compose -f docker-compose-picsure-loader.yml up --build` after `mvn -pl services/pic-sure-hpds/etl -am package
-DskipTests`. Size `HEAPSIZE` to the largest concept: finalization merges one concept at a time in memory, and a heap budget (half of
the max heap, about 400 bytes per observation) keeps several large concepts from running together.
