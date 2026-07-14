# pic-sure (monorepo)

Maven monorepo for the PIC-SURE API platform. A Spring Cloud Gateway front door plus focused Spring Boot services replace the WildFly-deployed `pic-sure-api-war` and its resource modules; the legacy WildFly code is removed as part of this migration.

## Layout

```
pic-sure/                       root pom — PARENT + aggregator
│                               edu.harvard.hms.dbmi.avillach:pic-sure-api:${revision}
├── platform/                   pic-sure-bom — the single version catalog (STANDALONE; no parent)
├── libs/
│   ├── pic-sure-commons/       pic-sure-common aggregator (subtree of hms-dbmi/pic-sure-common)
│   │   ├── pic-sure-api-model/     domain DTOs   (pkg edu.harvard.dbmi.avillach.{domain,util})
│   │   └── pic-sure-hpds-model/    HPDS query model (groupId …avillach.hpds)
│   └── pic-sure-logging-client/ audit/logging client (groupId edu.harvard.dbmi.avillach)
└── services/
    ├── pic-sure-gateway/            Spring Cloud Gateway MVC front door (routing, auth/audit, health)
    ├── pic-sure-operations-service/ config / dataset / query-persistence API
    └── pic-sure-hpds-query-service/ HPDS query + search + aggregate obfuscation
```

## Version strategy

| Line | Meaning |
|---|---|
| `3.0.0` (`${revision}`) | The monorepo line — Java 25, everything in the new reactor. Never publish it from the sibling repos. |
| `1.x` | Frozen Java 11 releases previously published for the shared libs (e.g. `pic-sure-api-model:1.0.0`, `pic-sure-logging-client:1.0.0`). Immutable — the frozen and `3.0.0` lines must never share a coordinate+version. |

Versions are CI-friendly (`${revision}` + `flatten-maven-plugin`). Dependency versions come from the `platform` BOM (`pic-sure-bom`), which imports the Spring Boot 3.5.x and Spring Cloud 2025.0.x BOMs. Internal modules inherit everything through the root parent; **external consumers import the published `pic-sure-bom` directly** instead of inheriting the parent (see `platform/README.md`).

> The BOM is standalone (no `<parent>`) by design — the root parent imports it, so a back-reference would create a model-resolution cycle. Its `spring-boot.version` / `spring-cloud.version` properties are duplicated from the root pom; bump both files in lockstep.

## Toolchain

**Java 25 is the standard and is enforced** (maven-enforcer requires JDK 25 at build time, compiler `--release 25`). `.sdkmanrc` pins `25.0.3-tem` (`sdk env` to activate).

## Build

```bash
# whole reactor (platform + libs + services)
mvn verify

# just the gateway (and what it needs)
mvn -pl services/pic-sure-gateway -am verify
```

Formatting: Spotless (Eclipse formatter, config inherited from the root) — `mvn spotless:apply`.

## Migration

Design docs and the implementation plan live under `docs/superpowers/`. This migration is tracked under Jira epic ALS-10463 and is delivered as a stack of sequential, independently-green PRs — reviewed and merged bottom-up onto the rewrite integration branch. The legacy WildFly modules (`pic-sure-api-war`, `pic-sure-api-data`, `pic-sure-util`, `pic-sure-resources`) are removed in the final PR of the stack.

## Git hooks

Two optional-but-recommended local hooks live in `code-formatting/`:

- `pre-commit.sh` — formats staged Java files with Spotless and blocks commits containing secrets (gitleaks).
- `pre-push.sh` — blocks pushes whose outgoing commits contain secrets (gitleaks).

Install both:

```sh
cp code-formatting/pre-commit.sh .git/hooks/pre-commit && chmod +x .git/hooks/pre-commit
cp code-formatting/pre-push.sh .git/hooks/pre-push && chmod +x .git/hooks/pre-push
```

Secret-scan false positives: add a `gitleaks:allow` trailing comment on the flagged line, or record
the finding's fingerprint in `.gitleaksignore` with a comment explaining why it is safe. CI runs the
same scan on every pull request (`.github/workflows/secrets-scan.yml`).
