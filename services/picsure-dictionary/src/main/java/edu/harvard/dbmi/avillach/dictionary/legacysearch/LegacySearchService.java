package edu.harvard.dbmi.avillach.dictionary.legacysearch;

import edu.harvard.dbmi.avillach.dictionary.concept.model.Concept;
import edu.harvard.dbmi.avillach.dictionary.filter.Filter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class LegacySearchService {

    private static final Logger log = LoggerFactory.getLogger(LegacySearchService.class);

    private final LegacySearchRepository legacySearchRepository;

    @Autowired
    public LegacySearchService(LegacySearchRepository legacySearchRepository) {
        this.legacySearchRepository = legacySearchRepository;
    }

    /**
     * Runs a free-text search. The raw term is turned into a postgres tsquery here rather than in a request mapper: the search endpoint now
     * binds a typed body, so the only thing left to translate is search syntax, which is a query concern.
     */
    public List<Concept> getSearchResults(String searchTerm, Pageable pageable) {
        String tsQuery = constructTsQuery(searchTerm == null ? "" : searchTerm);
        log.debug("Constructed Search Term: {}", tsQuery);
        return legacySearchRepository.getLegacySearchResults(new Filter(List.of(), tsQuery, List.of()), pageable);
    }

    /**
     * An attempt to provide OR search that will produce similar results to legacy search-prototype. Splitting on punctuation is also what
     * keeps a caller from injecting tsquery operators: only the tokens survive, and every operator in the result is one we wrote.
     */
    static String constructTsQuery(String searchTerm) {
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
