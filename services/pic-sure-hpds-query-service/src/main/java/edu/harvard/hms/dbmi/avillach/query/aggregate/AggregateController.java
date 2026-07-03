package edu.harvard.hms.dbmi.avillach.query.aggregate;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import edu.harvard.dbmi.avillach.domain.QueryRequest;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * The v1 aggregate/obfuscation ingress: {@code POST /hpds/open/query/sync}. Direct port of {@code AggregateDataSharingResourceRS}'s
 * {@code querySync} entry point (no inline audit -- Task 9: the gateway audits this path, see {@code AuditRouteTable}). Open-access: no
 * {@link edu.harvard.hms.dbmi.avillach.commons.identity.GatewayUser} guard here -- {@code WebSecurityConfig} already requires an
 * authenticated caller for all of {@code /hpds/**} (the "open"/"auth" distinction is about which HPDS backend answers the query and whether
 * its data is public, not about API-level authentication).
 *
 * <p><b>Coexistence with {@code HpdsQueryV1Controller}:</b> that controller maps the generic, path-variable
 * {@code /hpds/{backend}/query/sync} (any backend, including literally {@code "open"}). This controller maps the LITERAL
 * {@code /hpds/open/query/sync}. Spring MVC prefers a literal (no-variable) mapping over a variable one for the same request, so a request
 * to {@code /hpds/open/query/sync} is dispatched here (obfuscated), while {@code /hpds/auth/query/sync} -- and every other open-path
 * endpoint ({@code /hpds/open/query}, {@code /hpds/open/query/{id}/result}, etc.) -- still flows through the generic controller unmodified.
 * Only {@code query/sync} is intercepted because it is the only endpoint the WAR ever obfuscated (see {@link AggregateService}'s Javadoc);
 * this controller deliberately does NOT re-implement {@code /info}, {@code /search}, {@code /query}, {@code /query/{id}/status},
 * {@code /query/{id}/result}, or {@code /query/format}, since doing so under the literal {@code /hpds/open} prefix would shadow those
 * endpoints away from the generic controller for no obfuscation benefit (they were never obfuscated in the WAR either).
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
}
