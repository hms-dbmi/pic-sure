package edu.harvard.hms.dbmi.avillach.query.aggregate;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import edu.harvard.dbmi.avillach.domain.QueryStatus;
import edu.harvard.dbmi.avillach.logging.AuditEvent;
import edu.harvard.hms.dbmi.avillach.query.query.HpdsQueryRequest;
import edu.harvard.hms.dbmi.avillach.query.query.QueryRequestExamples;
import io.swagger.v3.oas.annotations.Hidden;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * The aggregate/obfuscation ingress: {@code POST /hpds/open/query/sync} and {@code POST /hpds/open/query}. {@link AggregateService} injects
 * the study-consents allow-list into the query's {@code select} field and calls HPDS under its configured API path. The gateway audits both
 * paths; the {@code @AuditEvent} labels here are declarative and nothing in this service intercepts them. There is no
 * {@link edu.harvard.hms.dbmi.avillach.commons.identity.GatewayUser} guard here because {@code WebSecurityConfig} already requires an
 * authenticated caller for all of {@code /hpds/**}; "open" names the HPDS backend that answers, not an unauthenticated route.
 *
 * <p><b>Coexistence with {@code HpdsQueryController}:</b> that controller maps the generic, path-variable {@code /hpds/{backend}/query} and
 * {@code /hpds/{backend}/query/sync}. This controller maps the LITERAL {@code /hpds/open/query} and {@code /hpds/open/query/sync}, which
 * Spring MVC prefers, so {@code /hpds/auth/query[/sync]} still flows through the generic controller. Only the two open submissions are
 * intercepted. The open-path read endpoints ({@code /query/{id}/status}, {@code /result}, {@code /signed-url}, {@code /metadata}) are left
 * to the generic controller: the async submit stores the rewritten, consent-scoped query through {@code QueryService}, so those reads
 * already operate on the safe stored query and re-implementing them here would only shadow the generic mappings.
 *
 * <p>The sync body binds {@link OpenQueryRequest}, which documents only what open access accepts and refuses genomic filters with a 400.
 * The hidden async submit still binds {@link HpdsQueryRequest}, the v3 request the generic controller binds. Both hand a typed v3 query to
 * {@link AggregateService}.
 *
 * <p>This controller is the open access OpenAPI document: {@code OpenApiGroupsConfig} publishes its paths as their own group, only when an
 * open backend is configured. The asynchronous submit is hidden from it because open access does not support participant-level results.
 */
@RestController
@RequestMapping("/hpds/open")
@Tag(
    name = "Open access",
    description = "Aggregate counts from the open access HPDS backend, for users without access to participant-level data. Small counts "
        + "are obfuscated, and genomic data and participant-level results are not available."
)
public class AggregateController {

    private static final String OPEN_EXAMPLE_NOTE =
        "Open access takes no genomic filters; a query with `genomicFilters` is answered with 400.";

    private static final String SYNC_DESCRIPTION = """
        Runs a query on the open access HPDS backend and returns aggregate counts in the response. Small counts are \
        obfuscated so that no individual participant can be identified. Send your PIC-SURE token if you have one; a \
        deployment that allows anonymous open access also accepts requests without one.

        | Result type | Result |
        |---|---|
        | COUNT | Obfuscated patient count |
        | CROSS_COUNT | Obfuscated patient count per study consent |
        | CATEGORICAL_CROSS_COUNT | Obfuscated counts per value of each categorical concept the filters name |
        | CONTINUOUS_CROSS_COUNT | Obfuscated counts per bin of each continuous concept the filters name |
        | OBSERVATION_CROSS_COUNT | Observation counts per concept in `select` |

        These are the only result types open access supports, and every one is answered inline in this response: open access \
        has no asynchronous submit, status, result or download.

        **Not supported on open access**
        - **Genomic data.** A query with `genomicFilters` is answered with 400, and the genomic result types \
        (INFO_COLUMN_LISTING, VARIANT_COUNT_FOR_QUERY, VARIANT_LIST_FOR_QUERY, VCF_EXCERPT, AGGREGATE_VCF_EXCERPT) are not \
        available. Genomic queries need the authorized backend, `POST /hpds/auth/query/sync`, and a token.
        - **Participant-level results.** PATIENTS and the DATAFRAME types are not available.
        - **`select` on a CROSS_COUNT.** It is ignored and replaced by every study consent.

        **Body**: phenotypic filters and a result type. Filter with `phenotypicClause`, name the concepts of an \
        OBSERVATION_CROSS_COUNT in `select`, and set `expectedResultType`. Find concept paths with the dictionary's \
        `POST /picsure/dictionary/concepts`.

        Pick a request body under **Examples** to fill the body, then **Execute**. Each example also runs from a shell; \
        paste it in place of `<example>`:

            curl -X POST "https://<host>/picsure/hpds/open/query/sync" \\
              -H "Content-Type: application/json" \\
              -d '<example>'
        """;

    private static final String COUNT_ABOVE_THRESHOLD_EXAMPLE = "1237 ±3";

    private static final String COUNT_BELOW_THRESHOLD_EXAMPLE = "< 10";

    private static final String CROSS_COUNT_EXAMPLE =
        "{\"\\\\_studies_consents\\\\\":\"1232 ±3\",\"\\\\_studies_consents\\\\phs000007\\\\\":\"< 10\"}";

    private static final String CATEGORICAL_CROSS_COUNT_EXAMPLE =
        "{\"\\\\demographics\\\\SEX\\\\\":{\"Female\":{\"count\":698,\"display\":\"698 ±3\",\"variance\":3},"
            + "\"Male\":{\"count\":0,\"display\":\"< 10\",\"variance\":9}}}";

    private static final String CONTINUOUS_CROSS_COUNT_EXAMPLE =
        "{\"\\\\demographics\\\\AGE\\\\\":{\"40 - 49\":{\"count\":348,\"display\":\"348 ±3\",\"variance\":3}}}";

    private final AggregateService service;

    public AggregateController(AggregateService service) {
        this.service = service;
    }

    @AuditEvent(type = "QUERY", action = "query.sync")
    @PostMapping(value = "/query/sync", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Run an open access query and return its obfuscated result", description = SYNC_DESCRIPTION)
    @ApiResponses(
        {@ApiResponse(
            responseCode = "200",
            description = "The obfuscated result. Its shape follows the query's expectedResultType, and the body is labelled "
                + "application/json for every result type. A CONTINUOUS_CROSS_COUNT whose study consent count is below the obfuscation "
                + "threshold answers 200 with an empty body.",
            content = @Content(
                mediaType = MediaType.APPLICATION_JSON_VALUE,
                schema = @Schema(
                    type = "string",
                    description = "COUNT: an obfuscated count as text, either a count with its variance or a below-threshold marker. "
                        + "CROSS_COUNT: a JSON object of study consent path to obfuscated count string. CATEGORICAL_CROSS_COUNT and "
                        + "CONTINUOUS_CROSS_COUNT: a JSON object of concept path to an object of value or bin to an object with count, "
                        + "display and variance. OBSERVATION_CROSS_COUNT: a JSON object of concept path to integer count."
                ),
                examples = {@ExampleObject(name = "COUNT at or above the threshold", value = COUNT_ABOVE_THRESHOLD_EXAMPLE),
                    @ExampleObject(name = "COUNT below the threshold", value = COUNT_BELOW_THRESHOLD_EXAMPLE),
                    @ExampleObject(name = "CROSS_COUNT", value = CROSS_COUNT_EXAMPLE),
                    @ExampleObject(name = "CATEGORICAL_CROSS_COUNT", value = CATEGORICAL_CROSS_COUNT_EXAMPLE),
                    @ExampleObject(name = "CONTINUOUS_CROSS_COUNT", value = CONTINUOUS_CROSS_COUNT_EXAMPLE)}
            )
        ), @ApiResponse(responseCode = "400", description = "Missing query data, a query with genomic filters, a result type open access does not serve, or a body that cannot be read as a query request."), @ApiResponse(responseCode = "502", description = "Aggregate backend call failed.")}
    )
    public ResponseEntity<String> querySync(
        @io.swagger.v3.oas.annotations.parameters.RequestBody(
            required = true,
            content = @Content(
                mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = OpenQueryRequest.class),
                examples = {
                    @ExampleObject(
                        name = "COUNT",
                        description = QueryRequestExamples.COUNT_DESCRIPTION + " The count is obfuscated.\n\n" + OPEN_EXAMPLE_NOTE,
                        value = OpenQueryExamples.COUNT
                    ),
                    @ExampleObject(
                        name = "CROSS_COUNT",
                        description = "Counts the matching patients in each study consent, each count obfuscated. "
                            + "`select` stays empty: open access replaces it with every study consent.\n\n" + OPEN_EXAMPLE_NOTE,
                        value = OpenQueryExamples.CROSS_COUNT
                    ),
                    @ExampleObject(
                        name = "CATEGORICAL_CROSS_COUNT",
                        description = QueryRequestExamples.CATEGORICAL_CROSS_COUNT_DESCRIPTION + " Each count is obfuscated.\n\n"
                            + OPEN_EXAMPLE_NOTE,
                        value = OpenQueryExamples.CATEGORICAL_CROSS_COUNT
                    ),
                    @ExampleObject(
                        name = "CONTINUOUS_CROSS_COUNT",
                        description = "For each continuous concept the filters name, counts the matching patients in each bin of its "
                            + "values, each count obfuscated.\n\n" + OPEN_EXAMPLE_NOTE,
                        value = OpenQueryExamples.CONTINUOUS_CROSS_COUNT
                    ),
                    @ExampleObject(
                        name = "OBSERVATION_CROSS_COUNT",
                        description = QueryRequestExamples.OBSERVATION_CROSS_COUNT_DESCRIPTION + "\n\n" + OPEN_EXAMPLE_NOTE,
                        value = OpenQueryExamples.OBSERVATION_CROSS_COUNT
                    )}
            )
        ) @RequestBody OpenQueryRequest req
    ) {
        return service.querySync(req.query() == null ? null : req.query().toQuery());
    }

    @Hidden
    @AuditEvent(type = "QUERY", action = "query.submitted")
    @PostMapping("/query")
    public QueryStatus query(@RequestBody HpdsQueryRequest req) {
        return service.query(req.query());
    }
}
