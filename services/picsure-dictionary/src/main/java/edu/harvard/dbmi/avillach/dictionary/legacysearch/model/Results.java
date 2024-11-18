package edu.harvard.dbmi.avillach.dictionary.legacysearch.model;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public record Results(@JsonProperty("searchResults") List<SearchResult> searchResults) {
}
