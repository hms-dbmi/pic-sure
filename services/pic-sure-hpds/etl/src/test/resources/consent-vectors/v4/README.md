# Consent test vectors v4

Review round 4 of PICSURELoader's combined input: the same study written in another spelling must never turn a data row of that study
into a global row (a copy into the patient's other partitions). v1, v2 and v3 are unchanged and still checked. Placeholder accessions
only (phs000000, phs999999, phs123456).

| File | Content |
|---|---|
| `combined-allConcepts-input.csv` | the routable input (25 lines below the header), described below |
| `case-collision-input.csv` | two valid consents whose studies differ only by case (`PHS999999.c1`, `phs999999.c2`): the run is refused, exit 2, before any work |
| `expected-partitions.csv` | `partition`, then the five input fields of every row that lands in that partition, sorted |
| `expected-counts.json` | routing counts, including `invalidStudyNodeRows`, `consentsSubtreeRowsNotConsents`, `studyFoldClashes` |
| `generate_expected.py` | derives the two expected files from the input, mirrors the loader's rules (`ConsentRouter`, `ConsentRowCheck`) and asserts the invariant (`python3 -I generate_expected.py combined-allConcepts-input.csv expected-partitions.csv expected-counts.json`). v4 adds the folding and accepts instants with an offset in the timestamp check |
| `SHA256SUMS` | checksums of the four data files (`sha256sum -c SHA256SUMS`); a change to a vector is a new version directory |

## The rule

Every `_consents` value (valid, invalid name, rejected row) registers its study as written and **folded**: U+FFFD, whitespace, control and
format characters removed, the part before the first dot, lower case. A data row whose node is a study as written goes by the v1-v3 rules.
A node that is not one but folds to a known study is unroutable (`INVALID_STUDY_NODE`), never global. Only a node that folds to no
consents value at all stays global (and is reported if it is a `phsNNNNNN`). Valid consents whose studies fold alike refuse the run.

## Patients

| Patient | Rows | Loader |
|---|---|---|
| 1 | valid `phs000000.c1`; a canonical `_consents` row with the text value `phs999999<U+FFFD>.c1` (passes the row check, the name is invalid); `\phs999999\X\`; a global row; `\_consents\y\` with text `flag` | **the reviewer's case 1**: `\phs999999\X\` is `INVALID_STUDY_NODE`, not a global row in `phs000000.c1` |
| 2 | valid `phs000000.c1`; text value `phs999999 .c1` (blank inside, invalid name); `\phs999999\X\` | **case 2**: `INVALID_STUDY_NODE` |
| 3 | valid `phs000000.c1` and `PHS999999.c1` (valid name, offset instant timestamp); `\phs999999\X\`, `\PHS999999\X\`, `\PHS000000\Y\` | **case 3**: `\phs999999\X\` and `\PHS000000\Y\` are `INVALID_STUDY_NODE`; `\PHS999999\X\` is a study row of the partition `PHS999999.c1` |
| 4 | valid `phs000000.c1`; `\phs123456\...` and `\Phs123456\...` (no consents value names that study); `\_consents\x\` with text `unrelated` | `phs123456` rows stay global (splitter parity, reported); the `\_consents\x\` row stays global and is counted |
| 5 | valid `phs000000.c1`; `\phs 999999\X\`, `\phs999999<U+FFFD>\X\` | `INVALID_STUDY_NODE` (the first by folding, the second by the path normalization) |

Expected: 2 partitions (`phs000000.c1`, `PHS999999.c1`), 21 lines, 0 consents rows rejected, 6 rows `INVALID_STUDY_NODE` (the run fails with
the default `maxRejectedRows`=0 and loads, exit 0, with `-Dpicsure.loader.maxRejectedRows=6`), `consentsSubtreeRowsNotConsents` = 2,
`studyNodesWithoutConsents` = `phs123456`. `case-collision-input.csv` exits 2 at any limit.

Not changed from the splitter, a separately tracked policy question: a study node that no `_consents` row names at all stays global.
