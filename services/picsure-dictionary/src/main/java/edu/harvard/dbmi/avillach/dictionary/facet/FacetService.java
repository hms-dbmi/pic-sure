package edu.harvard.dbmi.avillach.dictionary.facet;

import edu.harvard.dbmi.avillach.dictionary.filter.Filter;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Service
public class FacetService {
    public List<FacetCategory> getFacets(Filter filter) {
        return List.of();
    }

    public Optional<Facet> facetDetails(String facetCategory, String facet) {
        return Optional.empty();
    }
}
