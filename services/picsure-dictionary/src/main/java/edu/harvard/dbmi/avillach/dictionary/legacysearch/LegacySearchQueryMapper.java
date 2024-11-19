package edu.harvard.dbmi.avillach.dictionary.legacysearch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.harvard.dbmi.avillach.dictionary.filter.Filter;
import edu.harvard.dbmi.avillach.dictionary.legacysearch.model.LegacySearchQuery;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

@Component
public class LegacySearchQueryMapper {

    private static final ObjectMapper objectMapper = new ObjectMapper();
    private static final Logger log = LoggerFactory.getLogger(LegacySearchQueryMapper.class);

    public LegacySearchQueryMapper() {}

    public LegacySearchQuery mapFromJson(String jsonString) throws IOException {
        JsonNode rootNode = objectMapper.readTree(jsonString);
        JsonNode queryNode = rootNode.get("query");

        String searchTerm = queryNode.get("searchTerm").asText();
        int limit = queryNode.get("limit").asInt();
        searchTerm = constructTsQuery(searchTerm);
        log.debug("Constructed Search Term: {}", searchTerm);
        return new LegacySearchQuery(new Filter(List.of(), searchTerm, List.of()), PageRequest.of(0, limit));
    }

    // An attempt to provide OR search that will produce similar results to legacy search-prototype
    private String constructTsQuery(String searchTerm) {
        // Split on the | to enable or queries
        String[] orGroups = searchTerm.split("\\|");
        List<String> orClauses = new ArrayList<>();

        for (String group : orGroups) {
            // To replicate legacy search we will split using its regex [\\s\\p{Punct}]+
            String[] tokens = group.trim().split("[\\s\\p{Punct}]+");

            // Now we will combine the tokens in this group and '&' them together.
            String andClause = Arrays.stream(tokens).filter(token -> !token.isBlank()) // remove empty tokens.
                .map(token -> token + ":*") // add the wild card for search
                .collect(Collectors.joining(" & "));

            if (!andClause.isBlank()) {
                orClauses.add(andClause);
            }
        }


        return String.join(" | ", orClauses);
    }

}
