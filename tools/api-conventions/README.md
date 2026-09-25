# api-conventions

This module checks that every reactor controller carries a complete set of Swagger annotations: a `@Tag` or `@Hidden`, an `@Operation` summary, and at least one declared response including a 2xx. It also checks that `api-modules.properties` stays honest, so a new service cannot ship undocumented by accident.

It also holds every module to one authorization standard. A handler that needs an authority carries `@PreAuthorize("hasAnyAuthority('ADMIN', 'SUPER_ADMIN')")`, or `@PreAuthorize("hasAuthority('SUPER_ADMIN')")` for a single value. The values may name roles or privileges, written as literals. The rules reject `@RolesAllowed`, `@PermitAll`, `@DenyAll` and `@Secured` (`no-replaced-security-annotations`), `@PreAuthorize` anywhere but a handler method (`preauthorize-only-on-handlers`), and any other expression, `hasRole` included (`preauthorize-uses-standard-form`). `preauthorize-uses-standard-form` also rejects an authority that is not a public static String field of PSAMA's `AuthNaming.AuthRoleNaming`, since a misspelled name compiles and denies everyone. Add the field there before guarding a handler with a new authority. `guards-enable-method-security` fails a module that uses `@PreAuthorize` without `@EnableMethodSecurity`, because nothing would enforce its guards. Granted authorities in this reactor are bare privilege names with no `ROLE_` prefix, which is why `hasRole('ADMIN')` would match nobody. `no-role-checks` applies the same reasoning outside annotations: it fails any call to `hasRole`, `hasAnyRole` or `isUserInRole` on a Spring Security, Spring Boot actuator, Servlet or JAX-RS type, which catches a filter-chain `.hasRole(...)` URL rule or a `request.isUserInRole(...)` check that `preauthorize-uses-standard-form` cannot see.

The OpenAPI document publishes each guard's authorities as "Required authorities: ..." at the end of the operation description, so `docs-do-not-restate-authorities` fails a documented module whose `@Tag` description or `@Operation` summary or description names one of the authorities its guards require. That prose would only repeat the guard and drift from it.

## Rules

A failing build names the rule by its slug, for example `controller-tagged-or-hidden failed with 2 violation(s):`. Look the slug up here.

Registry, over every compiled module:

- `documented-module-has-controllers`: every module `api-modules.properties` marks `documented` was compiled and declares at least one controller.
- `controller-module-is-registered`: every module that declares a controller is listed in `api-modules.properties`.

Swagger documentation, over the modules marked `documented`:

- `controller-tagged-or-hidden`: every controller carries exactly one of `@Tag` or `@Hidden`.
- `tag-is-complete`: every `@Tag` has a non-blank name and a non-blank description.
- `operation-has-summary`: every handler in a tagged controller, unless it is `@Hidden`, carries an `@Operation` with a non-blank summary.
- `responses-are-declared`: every such handler declares at least one `@ApiResponse`, at least one of them a 2xx, and each with a valid code and a non-blank description.
- `docs-do-not-restate-authorities`: no `@Tag` description or `@Operation` summary or description names an authority that the module's guards require.

Authorization, over every compiled module:

- `no-replaced-security-annotations`: nothing carries `@RolesAllowed`, `@PermitAll`, `@DenyAll` or `@Secured`.
- `preauthorize-only-on-handlers`: `@PreAuthorize` sits only on controller handler methods.
- `preauthorize-uses-standard-form`: every `@PreAuthorize` is `hasAnyAuthority(...)` or `hasAuthority(...)` with distinct literal values, each a known authority.
- `guards-enable-method-security`: a module that uses `@PreAuthorize` declares `@EnableMethodSecurity` with pre/post support on.
- `no-role-checks`: no code calls `hasRole`, `hasAnyRole` or `isUserInRole` on a Spring Security, actuator, Servlet or JAX-RS type.

R14 covers every module. A handler that answers GET, through `@GetMapping` or a `@RequestMapping` whose `method` includes GET or is left empty, sets no `consumes` other than `*/*`. A GET carries no body, so clients send no `Content-Type`, and Spring never matches such a request to the handler. It answers 415, or hands the request to another mapping that fits the path: PSAMA's `GET /accessRule/allTypes` fell through to `GET /accessRule/{accessRuleId}` and answered 400 `Invalid UUID string: allTypes`. A `consumes` on the class's `@RequestMapping` counts too, since Spring applies it to every handler that does not declare its own. To satisfy the rule, drop `consumes` from the GET handler, or set `consumes = "*/*"` when a class level value would otherwise reach it.

It is not listed in the root pom's `<modules>` because it has to run after the reactor has compiled. With `-T1C`, Maven schedules modules by dependency graph rather than by declaration order, so a plain module entry gives no guarantee it runs last.

It reads compiled classes from each module's `target/classes` instead of declaring Maven dependencies on the services it checks. Every service repackages into a fat Spring Boot jar with its classes under `BOOT-INF/classes`, which is invisible to a dependent module.

`make verify` does not rebuild the reactor. If you edit a controller and run `make verify` without a fresh build, it checks the stale `target/classes` and can report green on code that no longer matches. Run `make build` first.

If `controller-module-is-registered` fires, a module declares a controller that `api-modules.properties` does not list. Add it there as `documented`, or as `internal: <reason>` if it is not a client API.

Because this module sits outside the root pom's `<modules>`, IntelliJ will not import it when you open the reactor from the root. Open it separately, and set `-Dreactor.root=<repo root>` on any test run started from the IDE.

These rules check annotations on compiled bytecode, not the OpenAPI document Springdoc actually serves. A green run here does not guarantee the published document is correct.
