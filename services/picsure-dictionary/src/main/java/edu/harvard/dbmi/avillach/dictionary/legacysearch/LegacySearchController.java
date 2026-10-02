package edu.harvard.dbmi.avillach.dictionary.legacysearch;

import edu.harvard.dbmi.avillach.dictionary.AuditAttributes;
import edu.harvard.dbmi.avillach.dictionary.filter.Filter;
import edu.harvard.dbmi.avillach.dictionary.legacysearch.model.LegacyResponse;
import edu.harvard.dbmi.avillach.dictionary.legacysearch.model.LegacySearchCriteria;
import edu.harvard.dbmi.avillach.dictionary.legacysearch.model.LegacySearchQuery;
import edu.harvard.dbmi.avillach.logging.AuditEvent;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;

@Controller
@Tag(name = "Legacy search", description = "The pre-dictionary search contract kept for older clients")
public class LegacySearchController {

    private final LegacySearchService legacySearchService;
    private final LegacySearchQueryMapper legacySearchQueryMapper;

    @Autowired
    private HttpServletRequest httpRequest;

    @Autowired
    public LegacySearchController(LegacySearchService legacySearchService, LegacySearchQueryMapper legacySearchQueryMapper) {
        this.legacySearchService = legacySearchService;
        this.legacySearchQueryMapper = legacySearchQueryMapper;
    }

    /**
     * Searches concepts for a client that still speaks the search contract that came before the dictionary.
     *
     * @param legacySearchQuery the bound request body
     * @return the matches in the legacy response shape, or an empty 400 when the body has no {@code query} object or its {@code limit} is
     *         missing or below 1
     */
    @Operation(summary = "Search in the legacy request and response shape")
    @ApiResponse(responseCode = "200", description = "Search results in the legacy response shape")
    @ApiResponse(responseCode = "400", description = "The body is not JSON, has no query object, or its limit is missing or below 1")
    @AuditEvent(type = "SEARCH", action = "search.legacy")
    @RequestMapping(path = "/search")
    public ResponseEntity<LegacyResponse> legacySearch(@RequestBody LegacySearchQuery legacySearchQuery) {
        LegacySearchCriteria criteria = legacySearchQuery.query();
        if (criteria == null || criteria.limit() == null || criteria.limit() < 1) {
            return ResponseEntity.badRequest().build();
        }
        Filter filter = legacySearchQueryMapper.toFilter(criteria);
        AuditAttributes.putMetadata(httpRequest, "search_term", filter.search());
        return ResponseEntity
            .ok(new LegacyResponse(legacySearchService.getSearchResults(filter, legacySearchQueryMapper.toPageable(criteria))));
    }

}
