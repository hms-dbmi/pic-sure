package edu.harvard.dbmi.avillach.dump.entities;

public record FacetCategoryMetaDump(String categoryName, String key, String value) implements DumpRow {
}
