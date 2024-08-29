package edu.harvard.dbmi.avillach.dictionary.filter;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import edu.harvard.dbmi.avillach.dictionary.facet.Facet;
import jakarta.annotation.Nullable;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record Filter(@Nullable List<Facet> facets, @Nullable String search, @Nullable List<String> consents) {
}
