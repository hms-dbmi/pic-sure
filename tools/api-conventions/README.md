# api-conventions

This module checks that every reactor controller carries a complete set of Swagger annotations: a `@Tag` or `@Hidden`, an `@Operation` summary, and at least one declared response including a 2xx. It also checks that `api-modules.properties` stays honest, so a new service cannot ship undocumented by accident.

It also holds every module to one authorization standard. A handler that needs an authority carries `@PreAuthorize("hasAnyAuthority('ADMIN', 'SUPER_ADMIN')")`, or `@PreAuthorize("hasAuthority('SUPER_ADMIN')")` for a single value. The values may name roles or privileges, written as literals. The rules reject `@RolesAllowed`, `@PermitAll`, `@DenyAll` and `@Secured` (`no-replaced-security-annotations`), `@PreAuthorize` anywhere but a handler method (`preauthorize-only-on-handlers`), and any other expression, `hasRole` included (`preauthorize-uses-standard-form`). `preauthorize-uses-standard-form` also rejects an authority that is not a public static String field of PSAMA's `AuthNaming.AuthRoleNaming`, since a misspelled name compiles and denies everyone. Add the field there before guarding a handler with a new authority. `guards-enable-method-security` fails a module that uses `@PreAuthorize` without `@EnableMethodSecurity`, because nothing would enforce its guards. Granted authorities in this reactor are bare privilege names with no `ROLE_` prefix, which is why `hasRole('ADMIN')` would match nobody. `no-role-checks` applies the same reasoning outside annotations: it fails any call to `hasRole`, `hasAnyRole` or `isUserInRole` on a Spring Security, Spring Boot actuator, Servlet or JAX-RS type, which catches a filter-chain `.hasRole(...)` URL rule or a `request.isUserInRole(...)` check that `preauthorize-uses-standard-form` cannot see.

The OpenAPI document publishes each guard's authorities as "Required authorities: ..." at the end of the operation description, so `docs-do-not-restate-authorities` fails a documented module whose `@Tag` description or `@Operation` summary or description names one of the authorities its guards require. That prose would only repeat the guard and drift from it.

`handler-has-audit-event` covers audit labels. The audit log reads each request's event type and action from `@AuditEvent` on the handler that served it, and a request that reaches the audit filter without a handler label is logged with event type `UNLABELED`. That covers a handler missing the annotation and a request turned away before any handler ran, such as a 401 or a 404, so an alert on `UNLABELED` with a status below 400 finds the missing annotations. The gateway is the exception: it logs `OTHER` when no entry in its route table matches. In every compiled module, `handler-has-audit-event` fails each handler that does not carry `@AuditEvent`. There is no exemption: a module where no handler carries it fails once per handler. The rule keys on the annotation rather than on an interceptor in the same module, because hpds declares its handlers in `services/pic-sure-hpds/service` and reads the annotation from an interceptor in `services/pic-sure-hpds/processing`. To satisfy it, give the new handler an `@AuditEvent(type = ..., action = ...)` that names what it does. `handler-has-audit-event` checks presence only. Tests such as PSAMA's `ControllerAuditEventTest` still pin the values.

`path-variables-in-template` checks request routing in every module with a controller. Each `@PathVariable` that names its variable, as `@PathVariable("userId")` or `@PathVariable(name = "userId")`, must appear as `{userId}` or `{userId:regex}` in at least one path the handler maps, counting every combination of the class-level `@RequestMapping` paths with the method's mapping paths. A variable no path declares is never bound, and Spring answers every request to that handler with a 500. Fix it by adding the segment to the mapping or by binding the value some other way. A `@PathVariable` with no explicit name is skipped, because its name comes from the compiled parameter name, which the checker cannot read; the reactor's `-parameters` flag and `DashboardDrawerControllerParameterNameTest` cover those.

The configuration rules read every `@Value` in every module, on fields, methods, constructor parameters and method parameters. Spring injects a malformed placeholder as literal text instead of failing at startup, so `@Value("${mail.subject")` hands the code the string `${mail.subject`. `value-strings-well-formed` parses each string the way Spring does: placeholders first, even inside an expression, each ending at the brace that balances its opening, with the key ending at the first top-level colon. It fails a placeholder or expression that is never closed, a closing brace left over, an empty `#{}`, and a key that is blank, built from another placeholder, or uses characters other than letters, digits, `.`, `-`, `_` and `[]`. `value-keys-declared` fails any key a module reads, defaults and nested keys included, that the module's `src/main/resources/META-INF/additional-spring-configuration-metadata.json` does not declare. Spring would resolve the key either way. The rule exists so each module keeps one list of every setting it reads, with its type and what it does, and so IDEs can complete and document those keys in properties files. Keys a `spring-boot-configuration-processor` run generates into `META-INF/spring-configuration-metadata.json` count as declared too. `property-metadata-complete` fails an entry in the hand-written file with no name, type or description, and a name declared twice. To add a setting, add the `@Value` and an entry such as `{"name": "mail.subject", "type": "java.lang.String", "description": "Subject line of the access-grant email."}`, with a `defaultValue` when the `@Value` carries one.

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

Audit labels, over every compiled module:

- `handler-has-audit-event`: every handler carries `@AuditEvent`.

Request mappings, over every compiled module:

- `path-variables-in-template`: every `@PathVariable` that names its variable appears as `{name}` in at least one path its handler maps.

Configuration, over every compiled module:

- `value-strings-well-formed`: every `@Value` string closes each placeholder and expression, leaves no brace over, and names a plain key in each placeholder.
- `value-keys-declared`: every key a module's `@Value` strings read is declared in that module's configuration metadata.
- `property-metadata-complete`: every entry in `additional-spring-configuration-metadata.json` has a name, a type and a description, and no name repeats.

It is not listed in the root pom's `<modules>` because it has to run after the reactor has compiled. With `-T1C`, Maven schedules modules by dependency graph rather than by declaration order, so a plain module entry gives no guarantee it runs last.

It reads compiled classes from each module's `target/classes` instead of declaring Maven dependencies on the services it checks. Every service repackages into a fat Spring Boot jar with its classes under `BOOT-INF/classes`, which is invisible to a dependent module.

`make verify` does not rebuild the reactor. If you edit a controller and run `make verify` without a fresh build, it checks the stale `target/classes` and can report green on code that no longer matches. Run `make build` first.

If `controller-module-is-registered` fires, a module declares a controller that `api-modules.properties` does not list. Add it there as `documented`, or as `internal: <reason>` if it is not a client API.

Because this module sits outside the root pom's `<modules>`, IntelliJ will not import it when you open the reactor from the root. Open it separately, and set `-Dreactor.root=<repo root>` on any test run started from the IDE.

These rules check annotations on compiled bytecode, not the OpenAPI document Springdoc actually serves. A green run here does not guarantee the published document is correct.
