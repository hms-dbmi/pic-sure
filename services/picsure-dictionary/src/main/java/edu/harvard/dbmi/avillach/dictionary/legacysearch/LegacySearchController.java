package edu.harvard.dbmi.avillach.dictionary.legacysearch;

import edu.harvard.dbmi.avillach.dictionary.AuditAttributes;
import edu.harvard.dbmi.avillach.dictionary.legacysearch.model.LegacyResponse;
import edu.harvard.dbmi.avillach.dictionary.legacysearch.model.LegacySearchQuery;
import edu.harvard.dbmi.avillach.logging.AuditEvent;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;

import java.io.IOException;

@Controller
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

    @AuditEvent(type = "SEARCH", action = "search.legacy")
    @RequestMapping(path = "/search")
    public ResponseEntity<LegacyResponse> legacySearch(@RequestBody String jsonString) throws IOException {
        LegacySearchQuery legacySearchQuery = legacySearchQueryMapper.mapFromJson(jsonString);
        AuditAttributes.putMetadata(
            httpRequest, "search_term", legacySearchQuery.filter().search() != null ? legacySearchQuery.filter().search() : ""
        );
        return ResponseEntity
            .ok(new LegacyResponse(legacySearchService.getSearchResults(legacySearchQuery.filter(), legacySearchQuery.pageable())));
    }

}
