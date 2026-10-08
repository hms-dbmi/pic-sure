package edu.harvard.dbmi.avillach.dictionary.legacysearch;

import edu.harvard.dbmi.avillach.dictionary.filter.Filter;
import edu.harvard.dbmi.avillach.dictionary.legacysearch.model.LegacySearchCriteria;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Turns the bound legacy search request into the filter and paging the dictionary's repositories take.
 */
@Component
public class LegacySearchQueryMapper {

    private static final Logger log = LoggerFactory.getLogger(LegacySearchQueryMapper.class);

    /**
     * Builds the concept filter for a legacy search. The search text becomes a PostgreSQL text search query: each {@code |} separated group
     * is split on whitespace and punctuation, every token matches by prefix, the tokens of a group are joined with AND and the groups with
     * OR. A missing search term searches with no text.
     *
     * @param criteria the bound {@code query} object of the request
     * @return a filter with no facets, no consents and the text search query as its search
     */
    public Filter toFilter(LegacySearchCriteria criteria) {
        String searchTerm = constructTsQuery(criteria.searchTerm() == null ? "" : criteria.searchTerm());
        log.debug("Constructed Search Term: {}", searchTerm);
        return new Filter(List.of(), searchTerm, List.of());
    }

    /**
     * Builds the paging for a legacy search, which always asks for the first page.
     *
     * @param criteria the bound {@code query} object of the request, with a limit of 1 or more
     * @return the first page, sized to the limit
     */
    public Pageable toPageable(LegacySearchCriteria criteria) {
        return PageRequest.of(0, criteria.limit());
    }

    private String constructTsQuery(String searchTerm) {
        String[] orGroups = searchTerm.split("\\|");
        List<String> orClauses = new ArrayList<>();

        for (String group : orGroups) {
            String[] tokens = group.trim().split("[\\s\\p{Punct}]+");
            String andClause =
                Arrays.stream(tokens).filter(token -> !token.isBlank()).map(token -> token + ":*").collect(Collectors.joining(" & "));

            if (!andClause.isBlank()) {
                orClauses.add(andClause);
            }
        }

        return String.join(" | ", orClauses);
    }

}
