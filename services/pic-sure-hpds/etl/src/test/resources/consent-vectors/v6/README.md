# Consent test vectors v6

Review round 6 of PICSURELoader's combined input: a consents-like row that breaks R1 or R2 is a rejected consents row, not a data row.
v1 to v5 are unchanged and still checked. Placeholder accessions only (phs000000, phs999999, phs123456).

| File | Content |
|---|---|
| `combined-allConcepts-input.csv` | 12 rows of 3 patients, described below |
| `expected-partitions.csv` | `partition`, then the five input fields of every row that lands in that partition, sorted |
| `expected-counts.json` | routing counts, including `consentRowsRejectedByReason` |
| `generate_expected.py` | derives the two expected files from the input, independent of the router (`python3 -I generate_expected.py combined-allConcepts-input.csv expected-partitions.csv expected-counts.json`) |
| `SHA256SUMS` | checksums of the three data files (`sha256sum -c SHA256SUMS`); a change to a vector is a new version directory |

## The rule

A path that mentions `_consents` (any case, also with U+FFFD inside) and breaks R1 (text before the first delimiter: `_consents\`,
`x\_consents\`) or R2 (empty study node, or one that normalization changes: `\\_consents\`) might be a consents row. Before this round
it was a data row that failed R1 or R2 (`MALFORMED_CONCEPT_PATH` or `INVALID_STUDY_NODE`, counted against `maxRejectedRows`) and the study
named in its text value was **not** registered. If that was the only row naming the study and an operator raised `maxRejectedRows`, the
well-formed rows of that study became global rows, copied into every partition of the patient. Now such a row is a rejected consents row:
it grants nothing, registers the study of its text value (exact and folded) and counts against `maxRejectedConsentRows`, under the problem
`MALFORMED_CONCEPT_PATH` (R1) or `INVALID_STUDY_NODE` (R2). Both passes decide it from the fields alone, before the field count and the
patient. Raising a limit stays safe: every row that might be a consents row registers its study.

Consequence for v5: the row of patient 7 (`_consents\` with the text value `phs000000.c1`) is now one of `consentRowsRejected` instead of
`unroutableRows` (`MALFORMED_CONCEPT_PATH`). The v5 files are unchanged; `ConsentStudyRegistrationTest` applies that one reclassification
to the v5 counts and `ConsentPathShapeTest` documents it.

## Patients

| Patient | Rows | Loader |
|---|---|---|
| 1 | valid `phs000000.c1`; `_consents\` with the text value `phs999999.c1` (the only row naming `phs999999`); `\phs999999\X\` | the consents-like row grants nothing and registers `phs999999`; `\phs999999\X\` is a study row of a study the patient holds no consent for: `NO_CONSENT`, **not** a global row in `phs000000.c1` |
| 2 | valid `phs000000.c1`; `x\_consents\` with the text value `phs123456.c1`; `\phs123456\Labs\Glucose\` | R1 again: `phs123456` is registered, its row is `NO_CONSENT` |
| 3 | valid `phs000000.c1`; `\\_consents\` with the text value `phs999999.c2`; `\_studies_consents\phs123456\Y\` | R2: rejected consents row; the row with the study id at depth 2 is a legitimate global |

Expected: 1 partition (`phs000000.c1`, 8 lines), 3 rejected consents rows (2 `MALFORMED_CONCEPT_PATH`, 1 `INVALID_STUDY_NODE`), 2 study rows
without consent, no unroutable row, `studiesOnlyInRejectedConsentRows` = `phs123456`, `phs999999`. The default run is refused
(`maxRejectedConsentRows`=0); with `-Dpicsure.loader.maxRejectedConsentRows=3` it loads and the `phs999999` and `phs123456` rows are
nowhere.
