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

**Two inputs.** The default input is the split one above: `<partition>-allConcepts.csv` files under the base directory. The other
is the **combined** file that production hpds-ingest reads (every study plus the global variables, `patient_num, concept_path, nval,
tval, timestamp`), given with `-Dpicsure.loader.input=<file>` (any file name, anywhere; for example `allConcepts_data_analyzer.csv`).
The output still goes under the base directory, which also holds `encryption_key`.

`-Dpicsure.loader.partitionBy` says what to do with a combined file (it is refused, exit 2, without `input`):

- `consent` (the default): the loader partitions the file itself, with the rules of the `split-allconcepts-by-consent` job of
  pic-sure-hpds-etl (`--globals=by-patient --partition-naming=suffix`, commit 6416d91, written to the summary as `consentRulesMatch`).
  Pass 1 reads the file and learns each patient's consents from the `\_consents\` rows (the text value is the consent). Pass 2 writes one
  routed CSV per consent under `<spoolDir>/.routed/`, and each is then loaded as a split partition, one at a time. Rows of a study (first
  path node = the study of a consent, `phs000000.c1` has the study `phs000000`) go to the patient's consents of that study; every other
  row, the `\_consents\` rows included, is copied to all of the patient's consents. A patient with no consent for a study row's study, or
  no consent at all for a global row, is dropped and counted (`NO_CONSENT`). A consent value that cannot name a partition (see the name
  rule above) is dropped for everybody and listed in the summary with its row count, the run goes on. The consent split is an
  access-control decision, so every doubtful row **fails closed**:
  - **A `_consents` row must be valid to grant a consent**: exactly 5 fields, a patient id that is an integer as written (no blanks),
    an empty nval, a timestamp that is empty, an integer or ISO. These are the splitter's rules (`SplitRowCheck`), with **one deliberate
    difference**: a `_consents` row with a non-blank nval does not grant, also when the number is valid (`NUMERIC_NVAL`). The splitter
    reads the consent from the text value and ignores the number; the loader would store that number while routing by the text, so
    the row would mean two things. A rejected row grants nothing, is written to no partition, is counted by reason
    (`routing.consentRowsRejectedByReason`) and recorded in `routing-failures.jsonl`.
  - **Only the canonical path may grant.** A row is a consents row when its path, as the partition's loader reads it (segments trimmed,
    U+FFFD removed, doubled and missing delimiters repaired, and a last segment equal to the text value dropped), is `\_consents\`. Only
    a path written exactly `\_consents\` or `µ_consentsµ` may grant. Every other spelling (`\_consents\ `, `\_consents`,
    `\_consents\\`, `  \_consents\`, `\_consents\phs999999.c1\` with that text value, `µ_consents\`) is rejected as
    `NON_CANONICAL_PATH`, in both passes alike, and is never routed as a global row.
  - **A rejected consents row still makes its study known.** For every `_consents` row that can be parsed into fields, the study of its
    trimmed text value (`phs999999.c1` gives `phs999999`) is registered, even when the row grants nothing. A data row of that study of a
    patient without a *valid* consent of it is dropped as `NO_CONSENT` (counted), never copied as a global row into the patient's other
    partitions. The studies that only rejected rows name are listed in `routing.studiesOnlyInRejectedConsentRows`. A node that no
    `_consents` row names at all (a `phsNNNNNN` with no consents rows anywhere) stays a global variable, as in the splitter, and is listed
    in `routing.studyNodesWithoutConsents` (a separately tracked policy question).
  - **A consents row on a line that cannot be parsed refuses the run (exit 2, before any work), whatever the limits**: its study cannot
    be known, so rows of it could not be kept out of the global rows. The message gives the physical line numbers. Fix the lines.
  - **Every line is one CSV record.** allConcepts has no line breaks inside a field, so a line that cannot be parsed (an unterminated
    quote) costs that line only (`MALFORMED_RECORD`, with its input line number; a line that mentions `_consents` refuses the run,
    see below), and the rows after it are routed. The splitter fails the whole job instead.
  - **A data row is exactly 5 fields** (the allConcepts contract); others are `MALFORMED_RECORD`. A missing or non-integer patient id, a
    header in the middle: unroutable too.
  - **A study node with leading or trailing whitespace is not routed** (`INVALID_STUDY_NODE`): `\ phs000000\A\` is `phs000000` once the
    partition's loader trims the path, but would be a global row (copied to every consent of the patient) as written. The same for a
    node with U+FFFD.
  - **Two limits, both default 0.** Rows that are not routed count against `maxRejectedRows`; rejected `_consents` rows count against
    `maxRejectedConsentRows`, independently. Above either the run ends with status `failed`, exit 1, before any partition is loaded.
    Set them to the numbers you accept, after reading `routing-failures.jsonl`; raising the second only lets the run go on without the
    rejected consents, it never grants them.
  Everything else (bad numbers, timestamps) is judged by the partition's loader as before.
- `none`: one flat store in the base directory (`allObservationsStore.javabin`, `columnMeta.javabin`, `columnMeta.csv`, as hpds-ingest
  writes it), for parity checks against hpds-ingest only. HPDS refuses such a directory when `hpds.requireAuthorizationFilter` is true,
  as it is in production.

A base directory that already holds a flat store is refused in `consent` mode, and one with partition stores is refused in `none`
mode (also with `overwrite`).

**Spool and routed files.** HPDS reads every subdirectory of the base as a partition, so with a combined input the spool is **outside
the base**: by default `<parent of base>/<base name>.picsure-spool` (the routed files are in `.routed` under it), and a
`picsure.loader.spoolDir` that is the base, inside it, or contains it (an ancestor) is refused (exit 2, before any work). The routed directory is deleted on every exit path (success, a failed
partition, a routing failure such as a full disk) unless `-Dpicsure.loader.keepRouted=true`; kept files stay outside the base and the
summary names them (`routing.routedDirKept`). **Disk:** besides the stores, the routing needs space for the routed files, about the size
of the input plus one copy of every global row for each consent of its patient; point `spoolDir` at fast local disk. The input is read
twice, one thread. **Heap of pass 1:** one map entry per patient with consents, about 60 bytes measured (2 million patients), so about 60 MB per million patients; allow 100 MB
(patients with the same consents share one set; pass 2 adds about 50 bytes per patient that has no consent at all). The routing keeps at
most `picsure.loader.maxOpenWriters` routed files open (default min(1024, open-file limit - 256), 128 if the limit is unknown; the value
used is logged and written to `routing.maxOpenWriters`); beyond it the least recently used is closed and reopened for append, which keeps
the row order and costs time (`routing.spoolWriterEvictions`). **The input is pinned**: its size and modification time are recorded
before pass 1 and the run is refused (exit 2, summary written, nothing loaded) if either changed by the end of pass 2, or if the two
passes disagree on the rows. **Stale partitions**: directories in the base that this run did not produce are listed in the summary as
`stalePartitions` and warned about, never deleted; HPDS would read them as partitions, so remove them or use a fresh base. `ingest-summary.json` gains `inputMode` (`split` or `combined`), `partitionBy`, `inputFile`, `inputSizeBytes`,
`consentRulesMatch` and a `routing` object (rows read, routed, per partition, copies of global rows, `NO_CONSENT` rows dropped,
patients without consent, invalid consent names with their rows, unroutable rows by reason, rejected `_consents` rows by reason, the studies only rejected rows name, the study nodes without any consents row). Record numbers in a partition's
`failures.jsonl` count the routed file, not the combined input.

Tuning is by system properties, passed in `JAVA_OPTS` (see `LoaderConfig` for the full list):

| Property | Default | |
|---|---|---|
| `picsure.loader.spoolDir` | split input: `<base>/.picsure-spool`; combined input: `<parent of base>/<base name>.picsure-spool` | spool root (a combined input routes into `.routed` under it, and may not be the base, inside it or contain it); one subdirectory per partition, removed afterwards; use fast local disk |
| `picsure.loader.keepRouted` | `false` | `true` keeps the routed files of a combined input after the run (outside the base) |
| `picsure.loader.maxOpenWriters` | min(1024, open-file limit - 256), 128 if unknown | routed files open at once while routing |
| `picsure.loader.input` | unset (split input) | a combined allConcepts file, routed by consent |
| `picsure.loader.partitionBy` | `consent` for a combined input | `consent` or `none` (flat store, parity checks only) |
| `picsure.loader.maxObservationsPerConcept` | `0` (no limit) | a concept that reaches a non-zero limit fails the partition (kept null markers count) |
| `picsure.loader.maxRejectedRows` | `0` | rows dropped for a patient id or number `CSVLoader` cannot parse that a partition tolerates; with a combined input also the rows that cannot be routed |
| `picsure.loader.maxRejectedConsentRows` | `0` | combined input: `_consents` rows that grant nothing (bad fields, `NON_CANONICAL_PATH`, ...) that the routing tolerates; each still makes its study known. A consents row on an unparsable line is never tolerated |
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
