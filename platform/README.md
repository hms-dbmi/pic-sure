# platform

Bill of Materials (BOM) for `pic-sure`. This module publishes a single, centralized set of dependency versions so every service and library in the repo — and any external consumer — can stay in sync without hardcoding versions individually.

## What this module is

`platform` is a `pom`-packaged module containing **only** a `<dependencyManagement>` block. It does not contain source code, does not get deployed as a runnable artifact, and does not carry build/plugin configuration (that belongs in the parent POM, not here).

Its sole job: **pin versions**, for two categories of dependencies used across `pic-sure`:

- **Third-party libraries** used across the monorepo, primarily via the imported `spring-boot-dependencies` and `spring-cloud-dependencies` BOMs
- **Internal artifacts** published from this repo (`pic-sure-api-model`, `pic-sure-hpds-model`, `pic-sure-logging-client`), so services always align on the current in-repo version

> **This BOM is standalone by design** — it has no `<parent>`. The root parent POM imports this BOM, so a `<parent>` pointing back at the root would create a model-resolution cycle. Its version properties (`spring-boot.version`, `spring-cloud.version`) are therefore duplicated from the root pom and must be bumped in lockstep.

## Why a separate module from the parent POM

Two different concerns get conflated if you don't split them:

| | `platform` (BOM) | parent POM |
|---|---|---|
| Purpose | Version pinning only | Shared plugin/build config, Java version, linting |
| Mechanism | `<scope>import</scope>` | `<parent>` inheritance |
| Can combine multiple? | Yes — import several BOMs | No — only one parent |
| Affects plugins/build? | No | Yes |

Keeping them separate means a module can pull in consistent versions via this BOM **without** being forced to inherit all of the monorepo's internal build/plugin conventions — useful for any library here that may eventually be consumed or published outside this repo.

## How to consume it

**Inside this repo (modules under the root parent):** do nothing. The root parent imports this BOM in its `<dependencyManagement>`, and every child module inherits it. Declare dependencies **without a version**:

```xml
<dependencies>
  <dependency>
    <groupId>com.fasterxml.jackson.core</groupId>
    <artifactId>jackson-databind</artifactId>
  </dependency>
  <dependency>
    <groupId>edu.harvard.hms.dbmi.avillach</groupId>
    <artifactId>pic-sure-api-model</artifactId>
  </dependency>
</dependencies>
```

**Outside this repo (sibling repos, not-yet-migrated services):** import the published BOM directly in your own `<dependencyManagement>` — you get the same versions without inheriting this repo's parent or build config:

```xml
<dependencyManagement>
  <dependencies>
    <dependency>
      <groupId>edu.harvard.hms.dbmi.avillach</groupId>
      <artifactId>pic-sure-bom</artifactId>
      <version>3.0.0</version>
      <type>pom</type>
      <scope>import</scope>
    </dependency>
  </dependencies>
</dependencyManagement>
```

## Adding or updating a version

1. Open `pom.xml` in this module.
2. Add or update the relevant entry inside `<dependencyManagement>`.
3. Bump the `platform` module's own version if this repo uses independent (non-lockstep) versioning; skip this step if versions are managed lockstep via `${revision}`.
4. Every consuming module picks up the change on its next build — no per-module edits required.

**Guidelines:**
- One version per artifact, defined once, here — never override a BOM-managed version locally in a consuming module's `pom.xml` unless there's a documented, temporary reason (leave a comment explaining why if you do).
- Keep third-party and internal-module entries grouped and commented for readability as the list grows.
- If bumping a widely-used dependency (e.g. Spring Boot, Jackson major version), coordinate with module owners before merging — a BOM change can affect every service in the repo at once.

## What does *not* belong here

- Actual source code or logic
- Plugin configuration, compiler settings, checkstyle/spotless rules → these belong in the parent POM
- Environment- or profile-specific configuration
- Dependencies that only one or two modules use and aren't shared broadly (just declare those locally with an explicit version in the consuming module)

## Related

- Root aggregator `pom.xml` — lists all modules including this one
- Parent POM — shared build/plugin configuration inherited separately from this BOM