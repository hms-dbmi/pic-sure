> Copied unchanged from `pipelines/hpds-pheno-load/tests/fixtures` of avillach-jenkins-bdc-etl (branch `integration`), except for this
> note. `gen-fixtures.sh`, which regenerates `split/` and `expected/`, stays there. `phs000000.c2` is deliberately malformed (see the
> stack README); the other four partitions are well formed.

# Fixtures for the hpds-pheno-load smoke test

All data here is synthetic. Accessions are the placeholders `phs000000`, `phs999999` and `phs123456`; patient numbers are made up.

- `split/` is what `split-allconcepts-by-consent` writes: headerless, fully quoted `<partition>-allConcepts.csv` files and `split_manifest.json`.
- `expected/<partition>.tsv` is `concept<TAB>observations`, computed by `gen-fixtures.sh` and not by a loader.
- `gen-fixtures.sh` regenerates both. `phs000000.c1` is written patient by patient so its 20 concepts interleave: loaded unsorted, `LoadingStore` (16 cached cubes) loses observations; the entrypoint's sort prevents it.
- `test-only-encryption_key` is a **TEST-ONLY** key. It is committed on purpose, protects nothing, and must never be used for real data or put into Secrets Manager. The real key is only ever mounted from a secret at run time.
