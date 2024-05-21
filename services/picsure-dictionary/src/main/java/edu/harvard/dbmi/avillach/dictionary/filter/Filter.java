package edu.harvard.dbmi.avillach.dictionary.filter;

import edu.harvard.dbmi.avillach.dictionary.facet.Facet;
import jakarta.annotation.Nullable;

import java.util.List;

public record Filter(@Nullable List<Facet> facets, @Nullable String search) {
}
