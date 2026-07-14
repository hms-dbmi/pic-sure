package edu.harvard.dbmi.avillach.dump.entities;

public record FacetMetaDump(String facetName, String categoryName, String key, String value) implements DumpRow {
}
