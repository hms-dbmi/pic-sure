package edu.harvard.dbmi.avillach.dictionary.facet;

import java.util.List;

public record FacetCategory(
    String name, String display, String description,
    List<Facet> facets
) {
    public FacetCategory(FacetCategory core, List<Facet> facets) {
        this(core.name(), core.display(), core.description(), facets);
    }
}
