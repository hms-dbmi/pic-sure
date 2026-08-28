# Operations binary and schema compatibility proof

This harness builds and starts three exact Operations generations against disposable MySQL, applies the reviewed Ticket 15 schema, and compares the observed results with [`matrix.tsv`](matrix.tsv). It uses the real management HTTP API for every write. Historical behavior is provided only by jars built from the pinned Git objects.

The matrix covers AIO, BDC, and AIM-AHEAD because Ticket 16 proves that their banner schema bytes match the Ticket 15 AIO contract consumed here. It records each binary's commit, source tree, jar SHA-256, `project.build.outputTimestamp`, pinned build/runtime images, migration commits and SQL checksums, schema state, result, and preserved-data checksum.

## Run it

Docker and Git are required. Run the complete matrix with:

```bash
tests/operations-binary-compatibility/test.sh all
```

Pass a matrix cell name instead of `all` for a focused run. The accepted names are in the `cell` column of `matrix.tsv`.

The default path fetches the exact Operations, Ticket 15, and Ticket 16 commits. Before those commits are public, provide clean local repositories:

```bash
OPERATIONS_COMPAT_SOURCE_ROOT=/path/to/pic-sure \
AIO_PROOF_SOURCE_ROOT=/path/to/PIC-SURE-Migrations \
BDC_PROOF_SOURCE_ROOT=/path/to/pic-sure-bdc-infrastructure \
tests/operations-binary-compatibility/test.sh all
```

The Operations repository must contain all three pinned commits. The migration repositories must have the exact required commit at `HEAD`. Every override must be clean; copied sources, moving branches, changed migrations, and untracked inputs are rejected. Set `KEEP_COMPAT_TEMP=true` to retain generated logs and the observed matrix after a diagnostic run. Logs contain only synthetic credentials and fixture data.

## Compatibility boundary

The supported upgrade path uses one writer generation at a time:

1. Stop banner management traffic.
2. Stop every old Operations writer.
3. Start final Operations against the forward schema.
4. Perform one final-generation publication so it reconciles the allocator above the live maximum.
5. Reopen management traffic.

Overlapping pre-allocator and final writers are deliberately recorded as `UNSUPPORTED_EXPECTED`. Both real binaries execute in that cell, after which the harness proves the single-writer recovery sequence.

The rollback cells are application rollback only. Final Operations writes occurrences and immutable versions, stops, and each old generation starts and reads the retained additive schema without changing the canonical occurrence/version dump. Before canonicalization, the harness separately checks exact fixture content and actors plus version start/effective timestamp provenance.

Database rollback is disaster recovery, not a Flyway down migration: stop all writers, restore a pre-migration backup, and accept loss of every post-backup write. This repository adds no reverse migration.

The occurrence-only cell documents the functional readiness boundary. Generic database health reaches `UP`, but a real publication fails because `banner_priority_allocator` is absent. The cell control-tests a pinned, synthetic BusyBox audit endpoint, enables the real logging client against it, and proves the failed transaction leaves zero occurrences, no version table, and no after-commit audit request.

## Scope

The proof is local and synthetic. It does not run Jenkins, access AWS or RDS, deploy an application, refresh PSAMA caches, or validate release-control ordering. Ticket 18 owns deployment sequencing, traffic control, and operator rollback integration.
