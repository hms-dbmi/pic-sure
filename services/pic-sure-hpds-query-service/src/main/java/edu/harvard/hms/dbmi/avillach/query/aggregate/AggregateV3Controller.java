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
 * The v3 aggregate/obfuscation ingress: {@code POST /hpds/open/v3/query/sync} and {@code POST /hpds/open/v3/query}. Same delegation as
 * {@link AggregateController} except it passes {@link AggregateVariant#V3} to {@link AggregateService}, which yields the {@code select}
 * consent field rather than {@code crossCountFields} and applies the {@code /v3} downstream HPDS prefix. The gateway audits both variants
 * identically; this controller does not emit audit events directly.
 *
 * <p><b>Coexistence with {@code HpdsQueryV3Controller}:</b> that controller maps the generic, path-variable
 * {@code /hpds/{backend}/v3/query} and {@code /hpds/{backend}/v3/query/sync}. This controller maps the LITERAL {@code /hpds/open/v3/query}
 * and {@code /hpds/open/v3/query/sync}, which Spring MVC prefers, so {@code /hpds/auth/v3/query[/sync]} still flows through the generic
 * controller. As with the v1 controller, only the two open submissions ({@code query/sync}, {@code query}) are intercepted -- the open-path
 * v3 read endpoints are deliberately left to the generic controller (see {@link AggregateController}'s Javadoc for the full rationale).
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
