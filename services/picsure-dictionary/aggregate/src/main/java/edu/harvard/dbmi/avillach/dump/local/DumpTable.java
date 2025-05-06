package edu.harvard.dbmi.avillach.dump.local;

public enum DumpTable {
    // The ordering of the enums in this list matters.
    // Each table should come before its dependant tables so that rows
    // can be dropped cleanly
    // spotless:off
    FacetConceptNode("facet__concept_node"),
    ConceptNodeMeta("concept_node_meta"),
    ConceptNode("concept_node"),
    FacetMeta("facet_meta"),
    Facet("facet"),
    FacetCategoryMeta("facet_category_meta"),
    FacetCategory("facet_category"),
    ;
    // spotless:on

    private final String sqlName;

    DumpTable(String sqlName) {
        this.sqlName = sqlName;
    }

    public String getSqlName() {
        return sqlName;
    }
}
