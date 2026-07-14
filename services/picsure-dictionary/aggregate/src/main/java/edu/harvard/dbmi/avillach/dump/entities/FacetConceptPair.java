package edu.harvard.dbmi.avillach.dump.entities;

public record FacetConceptPair(String facetName, String facetCategory, String conceptPath) implements DumpRow {
}
