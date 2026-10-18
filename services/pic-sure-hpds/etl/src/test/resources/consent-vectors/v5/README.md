# Consent test vectors v5

Review round 5 of PICSURELoader's combined input: two shapes of concept path that the router took for global rows although the
partition's loader stores them with no study node, and a study spelled with a supplementary format character. v1 to v4 are unchanged and
still checked. Placeholder accessions only (phs000000, phs999999, phs123456).

| File | Content |
|---|---|
| `combined-allConcepts-input.csv` | the v4 input (25 lines below the header) plus patients 6 and 7 (8 lines), described below |
| `expected-partitions.csv` | `partition`, then the five input fields of every row that lands in that partition, sorted |
| `expected-counts.json` | routing counts, including `invalidStudyNodeRows` and the new `malformedConceptPathRows` |
| `generate_expected.py` | derives the two expected files from the input, mirrors the loader's rules (`ConsentRouter`, `ConsentRowCheck`) and asserts the invariant (`python3 -I generate_expected.py combined-allConcepts-input.csv expected-partitions.csv expected-counts.json`). v5 adds the path-shape rule |
| `SHA256SUMS` | checksums of the three data files (`sha256sum -c SHA256SUMS`); a change to a vector is a new version directory |

## The rules

Both are proposed contract rules (ADR-0014 amendment). Both count against `maxRejectedRows`.

1. **R1: the path starts with a delimiter** (reason `MALFORMED_CONCEPT_PATH`). The text before the first delimiter (backslash or `µ`)
   must be empty. `phs999999\X\` is not (depth-1 node `X`, but the stored path names the study `phs999999`) and was a global row, copied
   under its own text into every partition of the patient. It applies to every row in combined mode, data rows and `_consents` rows
   alike: a consents-like path with a leading text (`_consents\`) never grants (it is not canonical, and as a data row it fails R1). A
   `_consents` row with a leading blank or other spelling that the loader reads as `\_consents\` keeps its v1 to v4 classification
   (`NON_CANONICAL_PATH`, rejected).
2. **R2: the study node is usable** (reason `INVALID_STUDY_NODE`). The first node after the leading delimiter must be non-empty, unchanged
   by normalization (`CsvObservationProducer.normalizeConceptPath`) and not another spelling of a consents study. `\\phs999999\X\`
   (empty node, stored as the study `phs999999` by no reader) was a global row; it is now `INVALID_STUDY_NODE`.

There is deliberately **no "study id at any depth" rule**: legitimate globals such as `\_studies_consents\phs123456\Y\` have a study
id at depth 2 and stay global.

Also in v5: study spellings fold by code point, so a supplementary format character (tag characters U+E0001 to U+E007F, category Cf) is
removed like a basic-plane one: `\phs999999<U+E0020>\X\` is another spelling of `phs999999` (`INVALID_STUDY_NODE`), never global.

## Patients 6 and 7 (patients 1 to 5 as in v4)

| Patient | Rows | Loader |
|---|---|---|
| 6 | valid `phs000000.c1` and `PHS999999.c1`; `phs999999\X\` (no leading backslash); `\\phs999999\X\` (empty depth-1 node); `\phs999999<U+E0020>\X\`; `\_studies_consents\phs123456\Y\` | `phs999999\X\` is `MALFORMED_CONCEPT_PATH` (R1); `\\phs999999\X\` and the tag character row are `INVALID_STUDY_NODE` (R2); the last is a legitimate global row in both partitions |
| 7 | `_consents\` with the text value `phs000000.c1` (no leading backslash); `\Global\G\` | the consents-like row grants nothing and is `MALFORMED_CONCEPT_PATH` (R1); the global row has no consent (`NO_CONSENT`) |

Expected: 2 partitions (`phs000000.c1`, `PHS999999.c1`), 27 lines, 10 unroutable rows (8 `INVALID_STUDY_NODE`, of which 6 from v4, and 2
`MALFORMED_CONCEPT_PATH`): the run fails with the default `maxRejectedRows`=0 and loads, exit 0, with `-Dpicsure.loader.maxRejectedRows=10`.

## Pending splitter changes

The splitter (`SplitMergedAllConceptsJob.segment` / `SplitRowCheck`, commit 6416d91) has the same gap as the loader had: it takes the
depth-1 node of the path as written, so `phs999999\X\` (node `X`) and `\\phs999999\X\` (empty node) are global rows there as well. To add
there, so that both agree (the loader is the stricter of the two): R1, reject a row whose text before the first delimiter is not empty
(`MALFORMED_CONCEPT_PATH`); R2, reject a row whose study node is empty (`INVALID_STUDY_NODE`); and remove format characters by code
point when it folds study spellings (if it does after the v4 changes).
