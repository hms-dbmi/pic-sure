package edu.harvard.hms.dbmi.avillach.query.aggregate;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import edu.harvard.dbmi.avillach.contracts.query.v3.QueryStatusResponse;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.Query;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * The aggregate/obfuscation ingress, and the only one: {@code POST /hpds/open/v3/query/sync} and {@code POST /hpds/open/v3/query}. The
 * unversioned sibling ({@code /hpds/open/query[/sync]}, {@code crossCountFields}, unprefixed downstream) was retired with the rest of the
 * v1 surface. No inline audit here -- the gateway audits this path, see {@code AuditRouteTable}.
 *
 * <p><b>Same bare contract as the generic ingress.</b> Both endpoints bind the v3 {@link Query} record directly -- no {@code QueryRequest}
 * envelope, no {@code resourceUUID}, no {@code resourceCredentials} -- so an open submission and an authorized one are the same wire shape,
 * and strict deserialization turns a leftover envelope field into a 400 rather than a silently dropped query. {@code /query} answers with
 * the shared {@link QueryStatusResponse}; {@code /query/sync} stays a {@code ResponseEntity<String>} passthrough because its body is the
 * OBFUSCATED payload, whose shape varies by result type (a {@code Map<String,String>} of concept path to obfuscated display value for the
 * cross counts, a bare string for COUNT) and which must not be re-serialized on the way out.
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
    public ResponseEntity<String> querySync(@RequestBody Query query) {
        return service.querySync(query);
    }

    @PostMapping("/query")
    public QueryStatusResponse query(@RequestBody Query query) {
        return service.query(query);
    }
}
