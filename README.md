# pic-sure (monorepo)

Maven monorepo for the PIC-SURE API platform — the gateway rewrite lands here as modules, replacing the WildFly-deployed `pic-sure-api-war` incrementally (strangler-fig). The legacy WAR remains in-repo but quarantined until decommission.

## Layout

```
pic-sure/                       root pom — PARENT + aggregator
│                               edu.harvard.hms.dbmi.avillach:pic-sure-api:${revision}
├── platform/                   pic-sure-bom — the single version catalog (STANDALONE; no parent)
├── libs/
│   ├── pic-sure-commons/       pic-sure-common aggregator (subtree of hms-dbmi/pic-sure-common)
│   │   ├── pic-sure-api-model/     domain DTOs   (pkg edu.harvard.dbmi.avillach.{domain,util})
│   │   └── pic-sure-hpds-model/    HPDS query model (groupId …avillach.hpds — frozen)
│   └── pic-sure-logging-client/ audit/logging client (groupId edu.harvard.dbmi.avillach — frozen)
├── services/
│   └── pic-sure-gateway/       Spring Cloud Gateway MVC front door (Phase 1: transparent)
└── pic-sure-legacy/            QUARANTINED — WildFly WAR, Java 11/javax, own parent pom,
                                NOT aggregated; builds independently
```

## Version strategy

| Line | Meaning |
|---|---|
| `3.0.0` (`${revision}`) | The monorepo line — Java 25, everything in the new reactor. Never publish it from the sibling repos. |
| `2.x` | The legacy WAR family (`pic-sure-legacy` parent is `pic-sure-api:2.2.0-SNAPSHOT`). |
| `1.x` | Frozen Java 11 releases the legacy WAR pins (e.g. `pic-sure-api-model:1.0.0`, `pic-sure-logging-client:1.0.0`). Immutable — the frozen and `3.0.0` lines must never share a coordinate+version. |

Versions are CI-friendly (`${revision}` + `flatten-maven-plugin`). Dependency versions come from the `platform` BOM (`pic-sure-bom`), which imports the Spring Boot 3.5.x and Spring Cloud 2025.0.x BOMs. Internal modules inherit everything through the root parent; **external consumers import the published `pic-sure-bom` directly** instead of inheriting the parent (see `platform/README.md`).

> The BOM is standalone (no `<parent>`) by design — the root parent imports it, so a back-reference would create a model-resolution cycle. Its `spring-boot.version` / `spring-cloud.version` properties are duplicated from the root pom; bump both files in lockstep.

## Toolchain

**Java 25 is the standard and is enforced** (maven-enforcer requires JDK 25 at build time, compiler `--release 25`). `.sdkmanrc` pins `25.0.3-tem` (`sdk env` to activate). The quarantined legacy tree pins its own `pic-sure-legacy/.sdkmanrc` (`11.0.30-tem`).

## Build

```bash
# whole new reactor (platform + libs + services); legacy is NOT included
mvn verify

# just the gateway (and what it needs)
mvn -pl services/pic-sure-gateway -am verify

# legacy WAR (independent build, JDK 11)
cd pic-sure-legacy && mvn package
```

Formatting: Spotless (Eclipse formatter, config inherited from the root) — `mvn spotless:apply`.

## Gateway migration

Design docs live in `docs/superpowers/` (local-only, gitignored). The migration is tracked under Jira epic ALS-10463; work happens on the long-lived `pic_sure_api_rewrite` branch, merged when complete.
