package edu.harvard.dbmi.avillach.dictionary.legacysearch;

import edu.harvard.dbmi.avillach.dictionary.AuditAttributes;
import edu.harvard.dbmi.avillach.dictionary.legacysearch.model.LegacyResponse;
import edu.harvard.dbmi.avillach.dictionary.legacysearch.model.LegacySearchQuery;
import edu.harvard.dbmi.avillach.logging.AuditEvent;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.io.IOException;

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

    @Operation(summary = "Search in the legacy request and response shape")
    @ApiResponse(responseCode = "200", description = "Search results in the legacy response shape")
    @AuditEvent(type = "SEARCH", action = "search.legacy")
    @PostMapping(path = "/search")
    public ResponseEntity<LegacyResponse> legacySearch(@RequestBody String jsonString) throws IOException {
        LegacySearchQuery legacySearchQuery = legacySearchQueryMapper.mapFromJson(jsonString);
        AuditAttributes.putMetadata(
            httpRequest, "search_term", legacySearchQuery.filter().search() != null ? legacySearchQuery.filter().search() : ""
        );
        return ResponseEntity
            .ok(new LegacyResponse(legacySearchService.getSearchResults(legacySearchQuery.filter(), legacySearchQuery.pageable())));
    }

}
