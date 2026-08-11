# pic-sure (monorepo)

Maven monorepo for the PIC-SURE API platform. A Spring Cloud Gateway front door plus focused Spring Boot services replace the removed
WildFly-deployed `pic-sure-api-war` and resource modules.

## Layout

```
pic-sure/                       root pom — PARENT + aggregator
│                               edu.harvard.hms.dbmi.avillach:pic-sure-api:${revision}
├── platform/                   pic-sure-bom — the single version catalog (STANDALONE; no parent)
├── libs/
│   ├── pic-sure-commons/       pic-sure-common aggregator (subtree of hms-dbmi/pic-sure-common)
│   │   ├── pic-sure-api-model/     domain DTOs   (pkg edu.harvard.dbmi.avillach.{domain,util})
│   │   └── pic-sure-hpds-model/    HPDS query model (groupId …avillach.hpds)
│   ├── pic-sure-logging-client/ audit/logging client (groupId edu.harvard.dbmi.avillach)
│   └── pic-sure-spring-commons/ DB-free shared Spring library
└── services/
│   ├── pic-sure-gateway/       Spring Cloud Gateway MVC front door
│   ├── pic-sure-operations-service/    reactor module
│   ├── pic-sure-hpds-query-service/    reactor module
│   ├── pic-sure-hpds/                  reactor module (imported from hms-dbmi/pic-sure-hpds)
│   ├── pic-sure-auth-microapp/         reactor module (imported from hms-dbmi/pic-sure-auth-microapp)
│   ├── pic-sure-logging/               reactor module (imported from hms-dbmi/PIC-SURE-Logging)
│   ├── picsure-dictionary/             reactor module (imported from hms-dbmi/picsure-dictionary)
│   └── pic-sure-visualization-service/ reactor module (imported from hms-dbmi/PIC-SURE-Visualization)
```

The imported services arrived as-is with full history (`git filter-repo` merge, consolidation
Phase 1) and were adopted onto the root parent + BOM + Java 25 in Phase 4. The legacy
WildFly modules have since been removed; every remaining service is part of this reactor.

## Modules

| Path | Status | Java | Jenkins jobs (AIO / FISMA) | Modernization |
|---|---|---|---|---|
| platform/ | reactor (BOM) | 25 | — | n/a |
| libs/* | reactor | 25 | (consumed in-reactor; install jobs retired) | done |
| services/pic-sure-gateway | reactor | 25 | PIC-SURE Gateway Build and Deploy / — | done |
| services/pic-sure-operations-service | reactor | 25 | — | done |
| services/pic-sure-hpds-query-service | reactor | 25 | — | done |
| services/pic-sure-hpds | reactor | 25 | PIC-SURE-HPDS Build / PIC-SURE HPDS Build | done (HTTP rationalization deferred, FO-1) |
| services/pic-sure-auth-microapp | reactor | 25 | PIC-SURE Auth Micro-App Build - Jenkinsfile / PIC-SURE Auth Micro App Build | done |
| services/pic-sure-logging | reactor | 25 (Javalin) | PIC-SURE Logging Build and Deploy / PIC-SURE Logging Build | done |
| services/picsure-dictionary | reactor | 25 | PIC-SURE Dictionary API Build and Deploy (+3 DB jobs) / PIC-SURE Dictionary Build | done |
| services/pic-sure-visualization-service | reactor | 25 | PIC-SURE Visualization Build and Deploy / PIC-SURE Visualization Build | done |

Standalone shared-library release jobs are retired: every service resolves the `3.0.0`
modules from this reactor. Previously published `1.x` artifacts remain immutable for
historical external consumers.

## Version strategy

| Line | Meaning |
|---|---|
| `3.0.0` (`${revision}`) | The monorepo line — Java 25, everything in the new reactor. Never publish it from the sibling repos. |
| `2.x` | Historical legacy-WAR releases; those modules are no longer in this repository. |
| `1.x` | Historical Java 11 shared-library releases (e.g. `pic-sure-api-model:1.0.0`, `pic-sure-logging-client:1.0.0`). Immutable — the `1.x` and `3.0.0` lines must never share a coordinate+version. |

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

Those commands need `pic-sure-bom` already in the local repository. Maven resolves an imported
BOM while building the root *model*, before any reactor module exists, so on an empty `~/.m2` it
looks the BOM up remotely and fails. Build it from the checkout first — `mvn -f platform/pom.xml
install` — or use `scripts/ci/run-maven-reactor.sh <maven args…>`, which does that install and then
runs the given command from the repository root. CI takes the latter route, which is why the build
needs no package-registry credentials.

Formatting: Spotless (Eclipse formatter, config inherited from the root) — `mvn spotless:apply`.
