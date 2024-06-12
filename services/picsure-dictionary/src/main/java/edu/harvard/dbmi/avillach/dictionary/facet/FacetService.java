package edu.harvard.dbmi.avillach.dictionary.facet;

import edu.harvard.dbmi.avillach.dictionary.filter.Filter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Service
public class FacetService {

    private final FacetRepository repository;

    @Autowired
    public FacetService(FacetRepository repository) {
        this.repository = repository;
    }

    public List<FacetCategory> getFacets(Filter filter) {
        return repository.getFacets(filter);
    }

    public Optional<Facet> facetDetails(String facetCategory, String facet) {
        return repository.getFacet(facetCategory, facet)
            .map(f -> new Facet(f, repository.getFacetMeta(facetCategory, facet)));
    }
}
