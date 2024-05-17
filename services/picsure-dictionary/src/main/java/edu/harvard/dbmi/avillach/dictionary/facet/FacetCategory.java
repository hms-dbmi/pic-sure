package edu.harvard.dbmi.avillach.dictionary.facet;

import java.util.List;

public record FacetCategory(
    String name, String display, String description,
    List<Facet> facets
) {
}
