package edu.harvard.hms.dbmi.avillach.query.aggregate;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import edu.harvard.dbmi.avillach.domain.QueryRequest;
import edu.harvard.dbmi.avillach.domain.QueryStatus;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * The v1 aggregate and obfuscation ingress: {@code POST /hpds/open/query/sync} and {@code POST /hpds/open/query}. The gateway audits these
 * paths through {@code AuditRouteTable}. There is no {@link edu.harvard.hms.dbmi.avillach.commons.identity.GatewayUser} guard here because
 * {@code WebSecurityConfig} already requires an authenticated caller for all of {@code /hpds/**} (the "open"/"auth" distinction is about
 * which HPDS backend answers the query and whether its data is public, not about API-level authentication).
 *
 * <p><b>{@code /hpds/open/query[/sync]} routing:</b> only this controller serves the literal {@code /hpds/open/query} and
 * {@code /hpds/open/query/sync} mappings, applying consent scoping and obfuscation. Open-path read endpoints
 * ({@code /hpds/open/v3/query/{id}/status}, {@code /result}, {@code /signed-url}, {@code /metadata}) flow through
 * {@link edu.harvard.hms.dbmi.avillach.query.query.HpdsQueryV3Controller} (the generic v3 ingress) instead.
 *
 * <p>Two open submissions are intercepted: {@code query/sync}, which applies obfuscation, and {@code query}, which applies consent scoping
 * to CROSS_COUNT requests before dispatch. The async submit delegates persistence and dispatch to {@code QueryService}, so the stored query
 * is the rewritten, consent-scoped one; subsequent read endpoints operate on that safe stored query. This controller deliberately does NOT
 * re-implement {@code /info}, {@code /search}, {@code /query/{id}/status}, {@code /query/{id}/result}, or {@code /query/format} under the
 * literal {@code /hpds/open} prefix -- doing so would shadow them away from the generic controller for no benefit because the read
 * operations already use the consent-scoped stored query.
 */
@RestController
@RequestMapping("/hpds/open")
@Tag(name = "aggregate-data-sharing (open)")
public class AggregateController {

    private final AggregateService service;

    public AggregateController(AggregateService service) {
        this.service = service;
    }

    @PostMapping(value = "/query/sync", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> querySync(@RequestBody QueryRequest req) {
        return service.querySync(req, AggregateVariant.V1);
    }

    @PostMapping("/query")
    public QueryStatus query(@RequestBody QueryRequest req) {
        return service.query(req, AggregateVariant.V1);
    }
}
