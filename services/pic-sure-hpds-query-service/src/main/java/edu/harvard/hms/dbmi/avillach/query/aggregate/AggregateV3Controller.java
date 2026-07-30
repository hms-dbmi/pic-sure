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
 * The aggregate/obfuscation ingress, and the only one: {@code POST /hpds/open/v3/query/sync} and {@code POST /hpds/open/v3/query}. It
 * passes {@link AggregateVariant#V3} to {@link AggregateService}, which yields the {@code select} consents field and the {@code /v3}
 * downstream HPDS prefix. The unversioned sibling ({@code /hpds/open/query[/sync]}, {@code crossCountFields}, unprefixed downstream) was
 * retired with the rest of the v1 surface. No inline audit here -- the gateway audits this path, see {@code AuditRouteTable}.
 *
 * <p>Open-access: no {@link edu.harvard.hms.dbmi.avillach.commons.identity.GatewayUser} guard here -- {@code WebSecurityConfig} already
 * requires an authenticated caller for all of {@code /hpds/**} (the "open"/"auth" distinction is about which HPDS backend answers the query
 * and whether its data is public, not about API-level authentication).
 *
 * <p><b>Coexistence with {@code HpdsQueryV3Controller}:</b> that controller maps the generic, path-variable
 * {@code /hpds/{backend}/v3/query} and {@code /hpds/{backend}/v3/query/sync}. This controller maps the LITERAL {@code /hpds/open/v3/query}
 * and {@code /hpds/open/v3/query/sync}, which Spring MVC prefers, so {@code /hpds/auth/v3/query[/sync]} still flows through the generic
 * controller.
 *
 * <p>Only the two open submissions are intercepted: {@code query/sync} (the only endpoint the WAR ever obfuscated -- see
 * {@link AggregateService}'s Javadoc) and {@code query} (the async submit, which the WAR consent-scoped via
 * {@code changeQueryToOpenCrossCount} for CROSS_COUNT before dispatch -- finding I6). The async submit delegates persistence + dispatch to
 * {@code QueryService}, so the STORED query is the rewritten, consent-scoped one; the open-path read endpoints
 * ({@code /hpds/open/v3/query/{id}/status}, {@code /result}, {@code /signed-url}, {@code /metadata}) are therefore deliberately left to
 * {@link edu.harvard.hms.dbmi.avillach.query.query.HpdsQueryV3Controller}, which operates on that safe stored query. Re-implementing
 * {@code /info}, {@code /search}, or the read ops under this literal prefix would shadow them away from the generic controller for no
 * benefit -- they were never obfuscated in the WAR either.
 */
@RestController
@RequestMapping("/hpds/open/v3")
@Tag(name = "aggregate-data-sharing (open, v3)")
public class AggregateV3Controller {

    private final AggregateService service;

    public AggregateV3Controller(AggregateService service) {
        this.service = service;
    }

    @PostMapping(value = "/query/sync", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> querySync(@RequestBody QueryRequest req) {
        return service.querySync(req, AggregateVariant.V3);
    }

    @PostMapping("/query")
    public QueryStatus query(@RequestBody QueryRequest req) {
        return service.query(req, AggregateVariant.V3);
    }
}
