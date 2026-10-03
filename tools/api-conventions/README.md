# api-conventions

This module checks that every reactor controller carries a complete set of Swagger annotations: a `@Tag` or `@Hidden`, an `@Operation` summary, and at least one declared response including a 2xx. It also checks that `api-modules.properties` stays honest, so a new service cannot ship undocumented by accident.

It also holds every module to one authorization standard. A handler that needs an authority carries `@PreAuthorize("hasAnyAuthority('ADMIN', 'SUPER_ADMIN')")`, or `@PreAuthorize("hasAuthority('SUPER_ADMIN')")` for a single value. The values may name roles or privileges, written as literals. The rules reject `@RolesAllowed`, `@PermitAll`, `@DenyAll` and `@Secured` (`no-replaced-security-annotations`), `@PreAuthorize` anywhere but a handler method (`preauthorize-only-on-handlers`), and any other expression, `hasRole` included (`preauthorize-uses-standard-form`). `preauthorize-uses-standard-form` also rejects an authority that is not a public static String field of PSAMA's `AuthNaming.AuthRoleNaming`, since a misspelled name compiles and denies everyone. Add the field there before guarding a handler with a new authority. `guards-enable-method-security` fails a module that uses `@PreAuthorize` without `@EnableMethodSecurity`, because nothing would enforce its guards. Granted authorities in this reactor are bare privilege names with no `ROLE_` prefix, which is why `hasRole('ADMIN')` would match nobody. `no-role-checks` applies the same reasoning outside annotations: it fails any call to `hasRole`, `hasAnyRole` or `isUserInRole` on a Spring Security, Spring Boot actuator, Servlet or JAX-RS type, which catches a filter-chain `.hasRole(...)` URL rule or a `request.isUserInRole(...)` check that `preauthorize-uses-standard-form` cannot see.

The OpenAPI document publishes each guard's authorities as "Required authorities: ..." at the end of the operation description, so `docs-do-not-restate-authorities` fails a documented module whose `@Tag` description or `@Operation` summary or description names one of the authorities its guards require. That prose would only repeat the guard and drift from it.

`catch-all-advice-extends-base` covers every module, libraries included. A `@ControllerAdvice` or `@RestControllerAdvice` class that declares an `@ExceptionHandler` for `Exception`, `RuntimeException` or `Throwable` must extend Spring's `ResponseEntityExceptionHandler`. The handled types are read from the annotation, or from the method's exception parameter when the annotation names none. Spring consults every advice before its own client-error mapping, so a catch-all that does not extend the base class turns an unreadable body, an unsupported media type, a wrong method or a missing parameter into a 500. To satisfy it, extend the base class and override `handleExceptionInternal` to write the service's own error body. A handler the service already declares for an exception the base class also handles must move into the matching base-class override, or Spring refuses to start with an ambiguous mapping.

`handler-has-audit-event` covers audit labels. The audit log reads each request's event type and action from `@AuditEvent` on the handler that served it, and a request that reaches the audit filter without a handler label is logged with event type `UNLABELED`. That covers a handler missing the annotation and a request turned away before any handler ran, such as a 401 or a 404, so an alert on `UNLABELED` with a status below 400 finds the missing annotations. The gateway is the exception: it logs `OTHER` when no entry in its route table matches. In every compiled module, `handler-has-audit-event` fails each handler that does not carry `@AuditEvent`. There is no exemption: a module where no handler carries it fails once per handler. The rule keys on the annotation rather than on an interceptor in the same module, because hpds declares its handlers in `services/pic-sure-hpds/service` and reads the annotation from an interceptor in `services/pic-sure-hpds/processing`. To satisfy it, give the new handler an `@AuditEvent(type = ..., action = ...)` that names what it does. `handler-has-audit-event` checks presence only. Tests such as PSAMA's `ControllerAuditEventTest` still pin the values.

`path-variables-in-template` checks request routing in every module with a controller. Each `@PathVariable` that names its variable, as `@PathVariable("userId")` or `@PathVariable(name = "userId")`, must appear as `{userId}` or `{userId:regex}` in at least one path the handler maps, counting every combination of the class-level `@RequestMapping` paths with the method's mapping paths. A variable no path declares is never bound, and Spring answers every request to that handler with a 500. Fix it by adding the segment to the mapping or by binding the value some other way. A `@PathVariable` with no explicit name is skipped, because its name comes from the compiled parameter name, which the checker cannot read; the reactor's `-parameters` flag and `DashboardDrawerControllerParameterNameTest` cover those.

`no-entity-parameters` covers every module. No controller handler parameter may be, or contain, a class annotated `@Entity`. The check follows generic arguments at any depth, array component types and `Optional`, so `List<Role>`, `Map<String, List<User>>`, `User[]` and `Optional<User>` all fail, and it applies to every parameter, not only `@RequestBody`. A class counts as an entity by its annotation, not its package, so `User.UserForDisplay` passes. Fields are not followed: a request record with an entity-typed field passes. A bound entity lets a caller set any column Jackson can reach. To satisfy it, bind a request record that lists only the fields the endpoint accepts, and map it onto the entity in the service.

`typed-handler-signatures` covers the modules marked `documented`, hidden controllers included. On every handler, the `@RequestBody` parameter type and the return type must name a concrete model. The check looks through `ResponseEntity`, `Mono`, `Optional`, `List`, `Set`, arrays and the type arguments of any other generic class, and at every level it rejects `Object`, a wildcard such as `ResponseEntity<?>`, a type variable, one of those five wrappers used raw, `Map` or any `java.util` map type, `JsonNode` or another Jackson tree type, Spring Data's `Page` or `Slice`, and a class annotated `@Entity`. `String`, `byte[]`, `InputStreamResource`, `Void` and `void` pass. An open shape publishes an empty schema, so a client cannot tell what to send or what comes back. To satisfy it, declare a record that mirrors the body the endpoint already emits. Where a handler returns `ResponseEntity<?>` because it builds both a success body and an error body, declare the success type and throw an exception for the error. The rule reads declared types only, so a `String` return whose body is JSON passes here, and the service's `OpenApiDocumentTest` is what catches it.

`schema-documented-models` covers the modules marked `documented` and the two shared model libraries, `libs/pic-sure-commons/pic-sure-api-model` and `libs/pic-sure-commons/pic-sure-hpds-model`. It starts at every handler's `@RequestBody` type and return type, hidden controllers included, and follows generic arguments, array components, superclasses, the subtypes a `@JsonSubTypes` names, and the type of every serialised field. It stops at JDK, Jackson, Spring and Reactor types. Every class it reaches needs a class-level `@Schema` description. Every field or record component needs a `@Schema` description, on the field or on its getter. Every field that is a scalar, or a collection or array of scalars, also needs an `example`. The scalars are the numeric and character primitives and their boxes, `String`, `UUID`, `Instant`, `Date` and `LocalDate`. Booleans, enums, nested models and maps need no example. Every enum needs a description on the type and on each constant. Static, synthetic and transient fields are skipped, and so is a field that carries `@JsonIgnore` on itself or on its getter. A violation that says no documented module or shared model library compiles a type means a model exposes a class the rule cannot open, so give that member a type the service owns. The convention, with the house example values, is in `libs/pic-sure-openapi/README.md`.

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

Error handling, over every compiled module:

- `catch-all-advice-extends-base`: a `@ControllerAdvice` or `@RestControllerAdvice` that handles `Exception`, `RuntimeException` or `Throwable` extends `ResponseEntityExceptionHandler`.

Audit labels, over every compiled module:

- `handler-has-audit-event`: every handler carries `@AuditEvent`.

Request mappings, over every compiled module:

- `path-variables-in-template`: every `@PathVariable` that names its variable appears as `{name}` in at least one path its handler maps.

Persistence, over every compiled module:

- `no-entity-parameters`: no controller handler parameter is, or contains, a class annotated `@Entity`.

Contracts, over the modules marked `documented`:

- `typed-handler-signatures`: every handler's `@RequestBody` type and return type names a concrete model, with no `Object`, wildcard, type variable, raw wrapper, map, Jackson tree type, Spring Data page or `@Entity` at any depth.
- `schema-documented-models`: every model reachable from a handler's `@RequestBody` type or return type, in those modules and in the two shared model libraries, carries a `@Schema` description on the type and on each serialised field or enum constant, and an example on each scalar field and each collection or array of scalars.

It is not listed in the root pom's `<modules>` because it has to run after the reactor has compiled. With `-T1C`, Maven schedules modules by dependency graph rather than by declaration order, so a plain module entry gives no guarantee it runs last.

It reads compiled classes from each module's `target/classes` instead of declaring Maven dependencies on the services it checks. Every service repackages into a fat Spring Boot jar with its classes under `BOOT-INF/classes`, which is invisible to a dependent module.

`make verify` does not rebuild the reactor. If you edit a controller and run `make verify` without a fresh build, it checks the stale `target/classes` and can report green on code that no longer matches. Run `make build` first.

If `controller-module-is-registered` fires, a module declares a controller that `api-modules.properties` does not list. Add it there as `documented`, or as `internal: <reason>` if it is not a client API.

Because this module sits outside the root pom's `<modules>`, IntelliJ will not import it when you open the reactor from the root. Open it separately, and set `-Dreactor.root=<repo root>` on any test run started from the IDE.

These rules check annotations on compiled bytecode, not the OpenAPI document Springdoc actually serves. A green run here does not guarantee the published document is correct.
