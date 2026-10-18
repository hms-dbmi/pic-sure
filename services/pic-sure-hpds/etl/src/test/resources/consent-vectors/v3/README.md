# Consent test vectors v3

Review round 3 of PICSURELoader's combined input: the last paths by which a `_consents` row could open access. v1 and v2 are
unchanged and still checked. Placeholder accessions only (phs000000, phs999999, phs123456, tutorial-biolincc_test).

| File | Content |
|---|---|
| `combined-allConcepts-input.csv` | the routable input (42 lines below the header), described below |
| `unparsable-consents-input.csv` | 4 lines, one of them a `_consents` row that cannot be parsed: the run is refused at every limit |
| `expected-partitions.csv` | `partition`, then the five input fields of every row that lands in that partition, sorted |
| `expected-counts.json` | routing counts, including `studiesOnlyInRejectedConsentRows` |
| `generate_expected.py` | derives the two expected files from the input. **It mirrors the loader's rules** (`ConsentRouter`, `ConsentRowCheck`), not the splitter's, and asserts the invariant below (`python3 -I generate_expected.py combined-allConcepts-input.csv expected-partitions.csv expected-counts.json`) |
| `SHA256SUMS` | checksums of the four data files (`sha256sum -c SHA256SUMS`); a change to a vector is a new version directory |

## The invariant

For every routed row with a study node, the partition it lands in has the study of that node, and the patient holds that partition
through a valid `_consents` row; a row is global only when its node is no study at all. A study is the study of the text value of **any**
`_consents` row, also a rejected one. `RoutedInvariant` (test) checks this on the routed output of v1, v2, v3 and of 250 random inputs.

## Patients

| Patient | Rows | Loader |
|---|---|---|
| 1 | valid `phs000000.c1` and `tutorial-biolincc_test` (the second through the legacy `µ_consentsµ`); `1,\_consents\,5,phs999999.c1,0` (numeric nval); data rows of `phs000000`, `phs999999`, `Global`, `tutorial-biolincc_test` | **the reviewer's example**: `\phs999999\X\` is dropped (`NO_CONSENT`), it does not land in `phs000000.c1` |
| 2 | valid `phs000000.c1` and data | plain |
| 3 | valid `phs000000.c1`; `3,\_consents\,garbage,phs123456.c1,0` (the only row in the file that names `phs123456`); a `\phs123456\Labs\Glucose\` row | `BAD_NVAL`; the study is known, the row is dropped |
| 4 to 11 | valid `phs000000.c1` and one consents row whose path is not written canonically, with a data row of the study it names | `NON_CANONICAL_PATH`, grants nothing, registers the study, the study row is dropped |
| 12 | only a rejected row (numeric nval) of `phs000000.c1` | no consent at all: its `phs000000` row and its global row are dropped |

The non-canonical paths: 4 `\_consents\ ` (trailing blank), 5 `\_consents` (no trailing delimiter), 6 `\_consents\\` (doubled), 7
`  \_consents\` (leading blanks), 8 `\_consents\phs999999.c1\` with text value `phs999999.c1` (the loader drops that last segment),
9 `µ_consents\` (mixed delimiters), 10 `\_cons<U+FFFD>ents\`, 11 `\ _consents \` (blanks inside). All of them normalize to `\_consents\`
in the partition's loader.

Expected: 2 partitions (`phs000000.c1`, `tutorial-biolincc_test`), 22 lines, 11 rows rejected (8 `NON_CANONICAL_PATH`, 2 `NUMERIC_NVAL`, 1
`BAD_NVAL`), 11 study rows and 1 global row dropped as `NO_CONSENT`, `studiesOnlyInRejectedConsentRows` = `phs123456`, `phs999999`,
`studyNodesWithoutConsents` empty.

Runs: with the default limits the run ends with status `failed`, exit 1, before any partition loads (11 consents rows against
`maxRejectedConsentRows`=0). With `-Dpicsure.loader.maxRejectedConsentRows=11` it loads (exit 0), and the invariant holds. Appending
the unparsable line of `unparsable-consents-input.csv` makes the run exit 2 at any limit.

## Differences from the splitter, all fail closed

Checked by running the real splitter (pic-sure-hpds-etl, commit 6416d91, arguments of the v1 README) on `combined-allConcepts-input.csv`
and comparing partition, patient, concept path, nval and tval. The splitter writes 3 partitions with 48 rows (`phs999999.c1` among
them); the loader 2 partitions with 22 rows. Every row the loader routes, the splitter routes to the same partition; the splitter also
routes:

1. **The numeric-nval consents** of patients 1 and 12 grant (the known v2 difference): patient 1 gets `phs999999.c1` (with patient 9, item 2: 8 rows), patient 12
   gets `phs000000.c1` and so its `phs000000` row and its global row.
2. **`µ_consents\` (patient 9) grants `phs999999.c1`**: the splitter replaces `µ` with a backslash and compares. The loader accepts
   only `\_consents\` and `µ_consentsµ`.
3. **The other non-canonical forms (patients 4 to 8, 10, 11) are global rows** there: copied to every consent of the patient, and
   they grant nothing. The loader rejects them.
4. **`phs123456` has no consent in the splitter** (its only consents row is `BAD_NVAL`, quarantined and forgotten), so the `phs123456`
   rows of patients 3 to 7 are global rows and land in `phs000000.c1`. In the loader the rejected row registers the study and the rows
   are dropped.

## Pending splitter changes

Rules the loader has that `SplitMergedAllConceptsJob` does not yet (the loader is the stricter of the two; v2 lists the others):

- **`NON_CANONICAL_PATH`**: a row whose path, once normalized as `CsvObservationProducer.normalizeConceptPath` does (and with a last
  segment equal to the text value dropped), is `\_consents\`, but which is not written exactly `\_consents\` or `µ_consentsµ`, is
  rejected and never copied as a global row;
- **a rejected `_consents` row registers its study**: the study of its trimmed text value (and, for a row with fewer than 4 fields,
  of its last field) is known, so that a data row of that study of a patient without a valid consent of it is dropped, not copied as a
  global row. Not changed in either tool: a study node that no `_consents` row names at all stays global and is reported
  (`STUDY_NODE_WITHOUT_CONSENTS`; a separately tracked policy question);
- a `_consents` row on a line that cannot be parsed ends the job at any `--max-reject-fraction`, because its study is unknown.
