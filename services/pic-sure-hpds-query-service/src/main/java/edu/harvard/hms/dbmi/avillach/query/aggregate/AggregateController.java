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
import io.swagger.v3.oas.annotations.Hidden;

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
 * <p>Both bodies bind {@link HpdsQueryRequest}, the same v3 request the generic controller binds, and hand its typed query to
 * {@link AggregateService}.
 *
 * <p>Hidden from the OpenAPI document. {@code HpdsQueryController} documents both backends under {@code /hpds/{backend}}, including what
 * the open backend serves, so callers see one set of query operations rather than two.
 */
@RestController
@RequestMapping("/hpds/open")
@Hidden
public class AggregateController {

    private final AggregateService service;

    public AggregateController(AggregateService service) {
        this.service = service;
    }

    @AuditEvent(type = "QUERY", action = "query.sync")
    @PostMapping(value = "/query/sync", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> querySync(@RequestBody HpdsQueryRequest req) {
        return service.querySync(req.query());
    }

    @AuditEvent(type = "QUERY", action = "query.submitted")
    @PostMapping("/query")
    public QueryStatus query(@RequestBody HpdsQueryRequest req) {
        return service.query(req.query());
    }
}
