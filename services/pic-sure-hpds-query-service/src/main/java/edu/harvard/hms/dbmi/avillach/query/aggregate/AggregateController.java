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
 * paths; this controller does not emit audit events directly. There is no
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
 * <p>Both bodies bind {@link HpdsQueryRequest}, the same v3 request the generic controller binds, and hand its typed query to
 * {@link AggregateService}.
 */
@RestController
@RequestMapping("/hpds/open")
@Tag(name = "aggregate-data-sharing (open)", description = "Open-access aggregate queries")
public class AggregateController {

    private final AggregateService service;

    public AggregateController(AggregateService service) {
        this.service = service;
    }

    @AuditEvent(type = "QUERY", action = "query.sync")
    @PostMapping(value = "/query/sync", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Run an open aggregate query inline")
    @ApiResponses(
        {@ApiResponse(
            responseCode = "200",
            description = "The obfuscated result. Its shape follows the query's expectedResultType, and the body is labelled "
                + "application/json for every result type, the plain-text ones included. A CONTINUOUS_CROSS_COUNT whose study consent "
                + "count is below the obfuscation threshold answers 200 with an empty body.",
            content = @Content(
                mediaType = MediaType.APPLICATION_JSON_VALUE,
                schema = @Schema(
                    type = "string",
                    description = "COUNT: an obfuscated count as text, either a count with its variance or a below-threshold marker. "
                        + "CROSS_COUNT: a JSON object of study consent path to obfuscated count string. CATEGORICAL_CROSS_COUNT and "
                        + "CONTINUOUS_CROSS_COUNT: a JSON object of concept path to an object of value or bin to an object with count, "
                        + "display and variance. INFO_COLUMN_LISTING, OBSERVATION_CROSS_COUNT, VARIANT_COUNT_FOR_QUERY, VCF_EXCERPT and "
                        + "AGGREGATE_VCF_EXCERPT: the HPDS body unchanged, as on the auth backend."
                ),
                examples = {@ExampleObject(name = "COUNT at or above the threshold", value = "1237 \u00b13"), @ExampleObject(
                    name = "COUNT below the threshold", value = "< 10"
                ), @ExampleObject(name = "CROSS_COUNT", value = "{\"\\\\_studies_consents\\\\\":\"1232 \u00b13\",\"\\\\_studies_consents\\\\phs000007\\\\\":\"< 10\"}"), @ExampleObject(name = "CATEGORICAL_CROSS_COUNT", value = "{\"\\\\demographics\\\\SEX\\\\\":{\"Female\":{\"count\":698,\"display\":\"698 \u00b13\",\"variance\":3}," + "\"Male\":{\"count\":0,\"display\":\"< 10\",\"variance\":9}}}"), @ExampleObject(name = "CONTINUOUS_CROSS_COUNT", value = "{\"\\\\demographics\\\\AGE\\\\\":{\"40 - 49\":{\"count\":348,\"display\":\"348 \u00b13\",\"variance\":3}}}"), @ExampleObject(name = "VARIANT_COUNT_FOR_QUERY with genomic filters", value = "{\"count\":17,\"message\":\"Query ran successfully\"}"), @ExampleObject(name = "VARIANT_COUNT_FOR_QUERY without genomic filters", value = "{\"count\":\"0\",\"message\":\"No variant filters were supplied, so no query was run.\"}")}
            )
        ), @ApiResponse(responseCode = "400", description = "Missing query data, a result type the open path does not serve, or a body that cannot be read as a query request"), @ApiResponse(responseCode = "502", description = "Aggregate backend call failed")}
    )
    public ResponseEntity<String> querySync(@RequestBody HpdsQueryRequest req) {
        return service.querySync(req.query());
    }

    @AuditEvent(type = "QUERY", action = "query.submitted")
    @PostMapping("/query")
    @Operation(summary = "Submit an open aggregate query")
    @ApiResponses(
        {@ApiResponse(responseCode = "200", description = "OK"),
            @ApiResponse(responseCode = "400", description = "Missing query data, or a body that cannot be read as a query request"),
            @ApiResponse(responseCode = "502", description = "Downstream aggregate or persistence call failed"),
            @ApiResponse(responseCode = "503", description = "Backend not configured"),
            @ApiResponse(responseCode = "504", description = "operations-service timed out")}
    )
    public QueryStatus query(@RequestBody HpdsQueryRequest req) {
        return service.query(req.query());
    }
}
