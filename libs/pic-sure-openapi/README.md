# pic-sure-openapi

One dependency that gives a service its live OpenAPI document. Adding `pic-sure-openapi` to a service pom brings springdoc, and the
auto-configuration in this module supplies the rest:

- the `info` block and the `bearerAuth` scheme (`OpenApiConfiguration`);
- "Required authorities: ..." at the end of each guarded operation's description (`RequiredAuthoritiesOperationCustomizer`);
- each enum constant's description (`EnumConstantDescriptionConverter`, `EnumDescriptionCustomizer`).

The module's test-jar ships `OpenApiDocumentAssertions`, which every service's `OpenApiDocumentTest` uses.

## The `@Schema` convention

It applies to every type a documented handler binds or returns, and to every type reachable from one.

| Where | Requirement |
|---|---|
| Record, class or enum | `@Schema(description = ...)` on the type |
| Field or record component | `description` |
| Scalar field: a numeric primitive or its box, `String`, `UUID`, `Instant`, `Date`, `LocalDate` | `example` as well |
| Collection or array of scalars | `example` as well, written as a JSON array |
| Boolean, enum, nested model, `Map` | no `example` |
| `Map` | the description names the keys |
| Enum constant | `@Schema(description = ...)` on the constant |
| Field the server always emits or always needs | `requiredMode = Schema.RequiredMode.REQUIRED` |
| Member marked `@JsonIgnore` | nothing |

Every `description` is one or more sentences, each ending in a period. A bare noun phrase becomes a sentence: "The query id." An
`@Operation` `summary` stays a short phrase with no period. The examples in this README follow the rule.

Nullability is stated in prose. A member the server always writes, possibly as JSON null, keeps `requiredMode = REQUIRED` and says
"Null when ..." in its description. `nullable = true` is not used.

A scalar whose only honest example is a value the deployment configures, such as an obfuscation variance, has no `example`, and
its description says why. The service's `OpenApiDocumentTest` names it in `assertSchemaDocumented(document, Set.of("Schema.property"),
...)`, which fails if the property gains an example or stops being a scalar of a checked schema.

A hidden controller's models follow the convention too. Hidden means absent from the document, not undocumented in code.

Examples are real domain values. Never `string`, `foo` or `example`. Use these wherever the kind of value appears, so every service
shows the same ones:

| Kind | Example |
|---|---|
| Study accession | `phs000007` |
| Consent | `phs000007.c1` |
| Concept path, continuous | `\demographics\AGE\` |
| Concept path, categorical | `\demographics\SEX\` |
| Categorical values | `["Male", "Female"]` |
| UUID | `8694e3d4-5cb4-410f-8431-993445e6d3f6` |
| ISO instant | `2026-09-30T14:05:00Z` |
| Epoch milliseconds | `1790777100000` |
| Email | `researcher@example.org` |
| Patient count | `1234` |
| Obfuscated count | `< 10` |
| Gene | `APOE` |

### Backslashes in an example

A scalar `String` example is copied into the document as written, so a concept path takes ordinary Java escapes:

    @Schema(description = "A concept path this filter must match.", example = "\\demographics\\SEX\\")

A collection example is parsed as JSON first, so each backslash is doubled again:

    @Schema(description = "Concept paths to select.", example = "[\"\\\\demographics\\\\AGE\\\\\", \"\\\\demographics\\\\SEX\\\\\"]")

Any example that parses as JSON is rendered as JSON, whatever the member's type. A `String` member that carries a JSON document
therefore shows an object under `type: string` in the document. No spelling avoids it: a quoted JSON string literal is rendered
verbatim, quotes and backslashes included. Give such a member its plain JSON example, say in its description that the value is one
string, and leave the rendering as it is.

### Enums

Describe the enum type and every constant. The document then shows, on every schema that lists the enum's values, a bullet per value
at the end of the description and the same text in the `x-enum-descriptions` extension, in `enum` order. A constant with no
description appears by name alone.

Do not put `allowableValues` on an enum-typed field. swagger-core replaces the real list of values with it.

An enum-typed field with no description of its own shows the enum type's description in the document, so the document cannot tell
that the field was left undescribed. The bytecode rule can.

## Testing a service's document

`OpenApiDocumentAssertions` reads the parsed `/v3/api-docs` body. `method` is lower case, `status` is a string, and a schema name is
its key under `components.schemas`.

| Assertion | Pins |
|---|---|
| `assertCovers` | every visible handler is in the document with a summary |
| `assertRequestSchema`, `assertRequestArrayOf` | the request body is the named schema, or a bare array of it |
| `assertResponseSchema` | the response is the named schema |
| `assertBareArrayOf`, `assertBareArrayOfScalar` | the response is a bare array, not an object wrapping one |
| `assertEnvelope` | the response is `{message, content}` around an array of the named schema |
| `assertMediaType` | a text or binary body declares its media type |
| `assertNoResponseBody` | a 204 declares no content |
| `assertSchemaHasFields` | the schema has the properties a client reads |
| `assertSchemaDocumented` | the schema meets the convention above, as far as the document shows it; under OpenAPI 3.1 a property that is a `$ref` needs its own description beside it |

A client that indexes a response (`res[0]`, `res.content[0]`, `resp.count`) depends on its shape. Pin that shape with one of these
whenever a handler's return type changes.

## Enforcement

Rules in `tools/api-conventions` check the convention on compiled bytecode and run in `make verify`. Each is listed here when it lands.

| Rule | Checks |
|---|---|

No rule has landed yet; the two that will are the last PRs of this stack.
