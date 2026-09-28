package edu.harvard.hms.dbmi.avillach.query.aggregate;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import edu.harvard.dbmi.avillach.domain.QueryRequest;
import edu.harvard.dbmi.avillach.domain.QueryStatus;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * The aggregate/obfuscation ingress: {@code POST /hpds/open/query/sync} and {@code POST /hpds/open/query}. {@link AggregateService} injects
 * the study-consents allow-list into the query's {@code select} field and calls HPDS on its {@code /v3} routes. The gateway audits both
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
 */
@RestController
@RequestMapping("/hpds/open")
@Tag(name = "aggregate-data-sharing (open)", description = "Open-access aggregate queries")
public class AggregateController {

    private final AggregateService service;

    public AggregateController(AggregateService service) {
        this.service = service;
    }

    @PostMapping(value = "/query/sync", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Run an open aggregate query inline")
    @ApiResponses(
        {@ApiResponse(responseCode = "200", description = "OK"),
            @ApiResponse(responseCode = "400", description = "Missing query data or an unsupported result type"),
            @ApiResponse(responseCode = "502", description = "Aggregate backend call failed")}
    )
    public ResponseEntity<String> querySync(@RequestBody QueryRequest req) {
        return service.querySync(req);
    }

    @PostMapping("/query")
    @Operation(summary = "Submit an open aggregate query")
    @ApiResponses(
        {@ApiResponse(responseCode = "200", description = "OK"), @ApiResponse(responseCode = "400", description = "Missing query data"),
            @ApiResponse(responseCode = "502", description = "Downstream aggregate or persistence call failed"),
            @ApiResponse(responseCode = "503", description = "Backend not configured"),
            @ApiResponse(responseCode = "504", description = "operations-service timed out")}
    )
    public QueryStatus query(@RequestBody QueryRequest req) {
        return service.query(req);
    }
}
