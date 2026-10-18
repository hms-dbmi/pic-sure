# Consent test vectors v1

Synthetic input for the combined-input mode of PICSURELoader (`picsure.loader.input`), with placeholder accessions only
(phs000000, phs999999, phs123456, tutorial-biolincc_test).

| File | Content |
|---|---|
| `combined-allConcepts-input.csv` | the combined allConcepts file: header row, `_consents` rows, study rows, global rows, bad rows |
| `expected-partitions.csv` | `partition`, then the five input fields of every row that lands in that partition, sorted |
| `expected-counts.json` | routing counts |
| `SHA256SUMS` | checksums of the three files above (`sha256sum -c SHA256SUMS`); a change to a vector is a new version directory |

What the input covers (patients by number):

- 1: consents of two studies (`phs000000.c1`, `tutorial-biolincc_test`); 2: two consents of one study (`phs000000.c1`, `.c2`);
  3: a hyphenated consent without a dot, whose own name is the study (`tutorial-biolincc_test`), declared with the legacy `µ` delimiter;
  4: a valid and an invalid consent (`phs123456.c1`, `phs123456.bad/name`); 5: only the invalid consent (every row of it is rejected);
  6: no consent at all; 7: a consent of `phs000000` but none of `phs123456`.
- `phs999999` has no consent: its node is a global variable. `DCC Harmonized data set` is a global variable. `_consents` rows are global rows.
- Not routable: a mid-file header (`subject_id,...`), a patient id `abc`, a row with 3 columns. Routed and rejected by the partition's
  loader: a number `2402L`.
- Timestamps: epoch milliseconds, `0`, empty, ISO with `Z`, `uuuu-MM-dd HH:mm:ss`, a date.

## Validation against the real splitter

The expected files were derived by an independent script and compared with the output of `SplitMergedAllConceptsJob`
(pic-sure-hpds-etl, commit 6416d91, branch split-by-consent), run as

```
java -jar hpds-etl.jar --job=split-allconcepts-by-consent --input=combined-allConcepts-input.csv --output=<dir> --fence-mapping=none \
  --globals=by-patient --partition-naming=suffix --timestamps=convert-iso --zero-timestamps=blank --bad-rows=quarantine \
  --max-reject-fraction=1 --force=true
```

Compared on routing (partition, patient, concept path, nval, tval; the splitter rewrites timestamps, the loader parses them itself):
the same four partitions, the same rows in each, with these intended differences: the splitter quarantines the `2402L` row (BAD_NVAL)
while the loader routes it to `phs000000.c1` and `phs000000.c2` and its partition loader rejects it (NUMERIC_PARSE_ERROR); the
splitter counts the header row, the mid-file header and the 3-column row as BAD_FIELD_COUNT / EMBEDDED_HEADER / BAD_PATIENT_NUM, the
loader as unroutable rows. Dropped copies for the invalid consent (7) and the rows without consent (3 study, 1 global) are the same.
