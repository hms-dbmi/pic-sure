# Consent test vectors v2

v1 plus the rows that exercise the validity gate of `_consents` rows and the study-node check (review round 2 of PICSURELoader's
combined input). v1 is unchanged and still checked. Placeholder accessions only (phs000000, phs999999, phs123456,
tutorial-biolincc_test).

| File | Content |
|---|---|
| `combined-allConcepts-input.csv` | the v1 file, then 24 appended rows (below) |
| `expected-partitions.csv` | `partition`, then the five input fields of every row that lands in that partition, sorted |
| `expected-counts.json` | routing counts, including `consentRowsRejected(ByReason)` and `invalidStudyNodeRows` |
| `generate_expected.py` | derives the two expected files from the input. **It mirrors the loader's rules** (`ConsentRouter`, `ConsentRowCheck`), not the splitter's: keep it in sync with them (`python3 -I generate_expected.py combined-allConcepts-input.csv expected-partitions.csv expected-counts.json`) |
| `SHA256SUMS` | checksums of the three data files (`sha256sum -c SHA256SUMS`); a change to a vector is a new version directory |

Appended rows (patients 8 to 14 have no other consent unless said):

| Row | Why it is there | Loader | Splitter (`--bad-rows=quarantine`) |
|---|---|---|---|
| `8,\_consents\,garbage,phs000000.c1,0` | nval is not a number | rejected `BAD_NVAL`, grants nothing | quarantined `BAD_NVAL`, same |
| `9,\_consents\,1,phs000000.c1,0` | nval is a valid number | rejected `NUMERIC_NVAL`, grants nothing | **grants** (reads the text value, ignores the number) |
| `10,\_consents\,,phs000000.c1` | 4 fields | rejected `BAD_FIELD_COUNT` | same |
| `11,\_consents\,,phs000000.c1,0,extra` | 6 fields | rejected `BAD_FIELD_COUNT` | same |
| `" 12",\_consents\,,phs000000.c1,0` | patient id with a blank, parsed untrimmed | rejected `BAD_PATIENT_NUM` | same |
| `13,\_consents\,,phs000000.c1,notatime` | timestamp is not blank, an integer or ISO | rejected `BAD_TIMESTAMP` | same |
| `14,\_consents\,,phs000000.c1,0` and `14,\_consents\,5,phs123456.c1,0` | one good consent and one with a number | the first grants, the second is rejected `NUMERIC_NVAL` | both grant |
| `8` to `15`: a `\phs000000\Demographics\Age\` row each; `14`: a `\phs123456\Labs\Glucose\` row; `8`: a `\DCC Harmonized data set\Age\` row | what the rejected consents would have opened | patients 8 to 13 have no consent: dropped (`NO_CONSENT`); 14 gets only `phs000000.c1` | patient 9 gets `phs000000.c1`, patient 14 also `phs123456.c1` |
| `15,"\_consents\,,phs000000.c1,0` | unterminated quote, and the line mentions `_consents` | rejected `UNPARSABLE_LINE` (a consents row: grants nothing), only that line | the whole job fails (`DATA_ERROR: Malformed delimited row`) |
| `1,"\phs000000\Demographics\Quote\,5,,` | unterminated quote | unroutable `MALFORMED_RECORD`, only that line; all other rows are routed | the whole job fails |
| `1,\phs000000\Demographics\Weight\,70,` (4 fields) and `1,\phs000000\Demographics\Waist\,80,,,extra` (6 fields) | a data row is exactly 5 fields | unroutable `MALFORMED_RECORD` | quarantined `BAD_FIELD_COUNT`, same |
| `1,\ phs000000\Invalid\Leading\,7,,` and `1,\phs000000 \Invalid\Trailing\,8,,` | the study node has leading or trailing whitespace: it is `phs000000` once the loader trims the path, but is not a study as written | unroutable `INVALID_STUDY_NODE`, written nowhere | global row, copied to every consent of patient 1 (`phs000000.c1`, `tutorial-biolincc_test`), where the loader would then read it as a `phs000000` study row |

## Differences from the splitter, all fail closed

Checked by running the real splitter (pic-sure-hpds-etl, commit 6416d91) with the arguments of the v1 README on this input without
its two unterminated-quote lines (which make the splitter fail the whole job) and comparing partition, patient, concept path, nval and
tval. Everything else is the same rows in the same partitions; the v1 difference (the `2402L`
number, routed here and rejected by the partition's loader) remains.

1. **A `_consents` row with a non-blank nval grants nothing** (`NUMERIC_NVAL`), also when the number is valid. The splitter ignores the
   nval. This is the one deliberate rule that the splitter does not have: the loader stores the number of that row while it routes by the
   text value, so the row would mean two things. Rows 9 and 14 (second) are the cases. A consent row carries its consent in the text value
   with an empty number, so no well-formed input changes.
2. **A row whose study node has leading or trailing whitespace (or U+FFFD) is not routed** (`INVALID_STUDY_NODE`) instead of being copied
   as a global row.
3. **Every line is one record.** A line that cannot be parsed costs that line (a consents-like one grants nothing); the splitter reads a
   quoted field across line breaks and fails the whole job on a bad quote. allConcepts has no line breaks inside a field.

Every line that is not routed counts toward `maxRejectedRows`, like every `_consents` row that breaks the contract: with the default 0 the
run is refused before any partition is loaded (in v2: 8 consents rows + 8 unroutable rows = 16, of which 3 are v1's).

## Pending splitter changes

Rules the loader has that `SplitMergedAllConceptsJob` does not yet, to add there so that both agree (the loader is the stricter of the
two):

- a `_consents` row with a non-blank nval does not grant (reject it with a new code, e.g. `CONSENT_ROW_HAS_NVAL`);
- a row whose depth-1 node differs from the depth-1 node of `CsvObservationProducer.normalizeConceptPath(path)` (trimmed segments, U+FFFD
  removed), i.e. a study node with leading or trailing whitespace, is rejected (`INVALID_STUDY_NODE`) rather than treated as a global
  variable (an ADR is proposed to make it a contract rule);
- a line that cannot be parsed is a counted bad row, not the end of the file.
