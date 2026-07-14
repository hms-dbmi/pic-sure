package edu.harvard.dbmi.avillach.dictionary.filter;

import edu.harvard.dbmi.avillach.dictionary.facet.Facet;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Function;

/**
 * Preprocesses filter input before query generation. Sorts facets and consents for stable cache keys, and sanitizes the search string to
 * prevent tsquery syntax errors from special characters.
 */
@Component
public class FilterProcessor {

    private static final int MAX_SEARCH_LENGTH = 200;

    public Filter processsFilter(Filter filter) {
        List<Facet> newFacets = filter.facets();
        List<String> newConsents = filter.consents();
        if (filter.facets() != null) {
            newFacets = new ArrayList<>(filter.facets());
            newFacets.sort(Comparator.comparing(Facet::name));
        }
        if (filter.consents() != null) {
            newConsents = new ArrayList<>(newConsents);
            newConsents.sort(Comparator.comparing(Function.identity()));
        }
        filter = new Filter(newFacets, sanitizeSearch(filter.search()), newConsents);
        return filter;
    }

    /**
     * Sanitizes a raw search string for safe use in PostgreSQL to_tsquery(). Strips all characters that are not Unicode letters, digits, or
     * whitespace — this prevents tsquery operator injection (&amp;, |, !, :, *, etc.). Collapses runs of whitespace and trims. Returns
     * empty string for input that contains only special characters (e.g. "&amp;"), which downstream code treats as no search.
     */
    static String sanitizeSearch(String search) {
        if (search == null) {
            return null;
        }
        if (search.length() > MAX_SEARCH_LENGTH) {
            search = search.substring(0, MAX_SEARCH_LENGTH);
        }
        return search.replaceAll("[^\\p{L}\\p{N}\\s]", " ").replaceAll("\\s+", " ").trim();
    }
}
