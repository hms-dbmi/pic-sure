package edu.harvard.dbmi.avillach.dictionary.legacysearch.model;

import com.fasterxml.jackson.annotation.JsonProperty;

public record LegacyResponse(@JsonProperty("results") Results results) {
}
