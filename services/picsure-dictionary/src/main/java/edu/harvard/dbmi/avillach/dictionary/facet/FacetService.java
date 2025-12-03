package edu.harvard.dbmi.avillach.dictionary.facet;

import edu.harvard.dbmi.avillach.dictionary.filter.Filter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class FacetService {

    private final FacetRepository repository;

    @Autowired
    public FacetService(FacetRepository repository) {
        this.repository = repository;
    }

    @Cacheable("facets")
    public List<FacetCategory> getFacets(Filter filter) {
        List<FacetCategory> facetCategories = repository.getFacets(filter);

        if (facetCategories.isEmpty()) {
            return facetCategories;
        }

        List<String> categoryNames = facetCategories.stream().map(FacetCategory::name).toList();
        Map<String, String> order = repository.getFacetCategoryOrder(categoryNames);

        if (order.isEmpty()) {
            return facetCategories.stream().sorted(Comparator.comparing(FacetCategory::display)).toList();
        }

        int unordered = order.values().stream().map(Integer::parseInt).reduce(Integer.MIN_VALUE, Math::max) + 1;
        return facetCategories.stream().sorted(Comparator.comparing((FacetCategory fc) -> {
            String orderValue = order.get(fc.name());
            return orderValue != null ? Integer.parseInt(orderValue) : unordered;
        }).thenComparing(FacetCategory::display)).toList();
    }

    public Optional<Facet> facetDetails(String facetCategory, String facet) {
        return repository.getFacet(facetCategory, facet).map(f -> new Facet(f, repository.getFacetMeta(facetCategory, facet)));
    }
}
