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
 * The v1 aggregate/obfuscation ingress: {@code POST /hpds/open/query/sync} and {@code POST /hpds/open/query}. Direct port of
 * {@code AggregateDataSharingResourceRS}'s {@code querySync}/{@code query} entry points (no inline audit -- the gateway audits this path,
 * see {@code AuditRouteTable}). Open-access: no {@link edu.harvard.hms.dbmi.avillach.commons.identity.GatewayUser} guard here --
 * {@code WebSecurityConfig} already requires an authenticated caller for all of {@code /hpds/**} (the "open"/"auth" distinction is about
 * which HPDS backend answers the query and whether its data is public, not about API-level authentication).
 *
 * <p><b>Coexistence with {@code HpdsQueryV1Controller}:</b> that controller maps the generic, path-variable {@code /hpds/{backend}/query}
 * and {@code /hpds/{backend}/query/sync} (any backend, including literally {@code "open"}). This controller maps the LITERAL
 * {@code /hpds/open/query} and {@code /hpds/open/query/sync}. Spring MVC prefers a literal (no-variable) mapping over a variable one for
 * the same request, so those two open-path submissions are dispatched here (consent-scoped / obfuscated), while
 * {@code /hpds/auth/query[/sync]} -- and the remaining open-path read endpoints ({@code /hpds/open/query/{id}/status}, {@code /result},
 * {@code /signed-url}, {@code /metadata}) -- still flow through the generic controller unmodified.
 *
 * <p>Two open submissions are intercepted: {@code query/sync} (the only endpoint the WAR ever obfuscated -- see {@link AggregateService}'s
 * Javadoc) and {@code query} (the async submit, which the WAR consent-scoped via {@code changeQueryToOpenCrossCount} for CROSS_COUNT before
 * dispatch -- finding I6). The async submit delegates persistence + dispatch to {@code QueryService}, so the STORED query is the rewritten,
 * consent-scoped one; the subsequent read endpoints therefore stay on the generic controller and operate on the safe stored query. This
 * controller deliberately does NOT re-implement {@code /info}, {@code /search}, {@code /query/{id}/status}, {@code /query/{id}/result}, or
 * {@code /query/format} under the literal {@code /hpds/open} prefix -- doing so would shadow them away from the generic controller for no
 * benefit (they were never obfuscated in the WAR, and the read ops already operate on the consent-scoped stored query).
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
