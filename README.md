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
│   ├── pic-sure-gateway/       Spring Cloud Gateway MVC front door
│   ├── pic-sure-operations-service/    reactor module
│   ├── pic-sure-hpds-query-service/    reactor module
│   ├── pic-sure-hpds/                  QUARANTINED import (hms-dbmi/pic-sure-hpds)
│   ├── pic-sure-auth-microapp/         QUARANTINED import (hms-dbmi/pic-sure-auth-microapp)
│   ├── pic-sure-logging/               QUARANTINED import (hms-dbmi/PIC-SURE-Logging)
│   ├── picsure-dictionary/             QUARANTINED import (hms-dbmi/picsure-dictionary)
│   ├── pic-sure-services/              QUARANTINED import (hms-dbmi/pic-sure-services)
│   └── pic-sure-visualization-service/ QUARANTINED import (hms-dbmi/PIC-SURE-Visualization)
├── pic-sure-shadow-reconciler/ reactor module (parity verification tooling)
└── pic-sure-legacy/            QUARANTINED — WildFly WAR, Java 11/javax, own parent pom,
                                NOT aggregated; builds independently
```

QUARANTINED = imported as-is with full history (`git filter-repo` merge), keeps its own
parent POM/JDK, builds independently from its subdirectory, NOT in the root reactor.
Modernization onto the root parent + BOM happens per service in consolidation Phase 4.

## Modules

| Path | Status | Java | Jenkins jobs (AIO / FISMA) | Modernization |
|---|---|---|---|---|
| platform/ | reactor (BOM) | 25 | — | n/a |
| libs/* | reactor | 25 | Common Install / Common Build (frozen-branch libs, see below) | done |
| services/pic-sure-gateway | reactor | 25 | PIC-SURE Gateway Build and Deploy / — | done |
| services/pic-sure-operations-service | reactor | 25 | — | done |
| services/pic-sure-hpds-query-service | reactor | 25 | — | done |
| services/pic-sure-hpds | reactor | 25 | PIC-SURE-HPDS Build / PIC-SURE HPDS Build | done (HTTP rationalization deferred, FO-1) |
| services/pic-sure-auth-microapp | reactor | 25 | PIC-SURE Auth Micro-App Build - Jenkinsfile / PIC-SURE Auth Micro App Build | done |
| services/pic-sure-logging | reactor | 25 (Javalin) | PIC-SURE Logging Build and Deploy / PIC-SURE Logging Build | done |
| services/picsure-dictionary | reactor | 25 | PIC-SURE Dictionary API Build and Deploy (+3 DB jobs) / PIC-SURE Dictionary Build | done |
| services/pic-sure-services | reactor | 25 | PIC-SURE Build and Deploy Uploader / — | done |
| services/pic-sure-visualization-service | reactor | 25 | PIC-SURE Visualization Build and Deploy / PIC-SURE Visualization Build | done |
| pic-sure-legacy/ | quarantined | 11 | PIC-SURE-API Build / PIC-SURE API Build | decommission (rewrite Phase 7) |

Frozen shared libs: RETIRED (consolidation Phase 4 complete) — every service resolves the
`3.0.0` reactor line; the Jenkins install jobs are deleted. The local `frozen/legacy-java11`
branches in pic-sure-common / PIC-SURE-Logging-Client remain unpushed history only; the
sibling lib repos get archived at push day. pic-sure-legacy still pins the released
`1.0.0` artifacts from GitHub Packages (unaffected).

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
