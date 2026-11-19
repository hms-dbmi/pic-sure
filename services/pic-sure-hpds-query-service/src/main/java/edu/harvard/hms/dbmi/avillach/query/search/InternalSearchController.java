package edu.harvard.hms.dbmi.avillach.query.search;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import edu.harvard.dbmi.avillach.domain.SearchResults;
import edu.harvard.dbmi.avillach.logging.AuditEvent;
import io.swagger.v3.oas.annotations.Hidden;

/**
 * Internal-only concept search at {@code POST /internal/search/{backend}}, for trusted services inside the network. It is gated by
 * {@link edu.harvard.hms.dbmi.avillach.commons.security.InternalTokenFilter}, which requires the {@code X-PIC-SURE-INTERNAL-TOKEN} header,
 * and is not part of the public API or the OpenAPI document. Delegates to {@link SearchService}.
 */
@RestController
@Hidden
public class InternalSearchController {

    private final SearchService service;

    public InternalSearchController(SearchService service) {
        this.service = service;
    }

    /**
     * Searches concepts on a backend.
     *
     * @param backend the {@code {backend}} path segment, {@code auth} or {@code open}
     * @param req the search request
     * @return the concepts and variant annotations that match the search term
     */
    @AuditEvent(type = "SEARCH", action = "search.execute")
    @PostMapping("/internal/search/{backend}")
    public SearchResults search(@PathVariable("backend") String backend, @RequestBody SearchRequest req) {
        return service.search(backend, req);
    }
}
