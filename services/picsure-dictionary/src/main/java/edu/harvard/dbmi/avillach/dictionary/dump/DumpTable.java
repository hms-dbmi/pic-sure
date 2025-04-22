package edu.harvard.dbmi.avillach.dictionary.dump;

public enum DumpTable {
    // spotless:off
    ConceptNode("concept_node"),
    ConceptNodeMeta("concept_node_meta"),
    Facet("facet"),
    FacetConceptNode("facet__concept_node"),
    FacetCategory("facet_category"),
    FacetCategoryMeta("facet_category_meta"),
    FacetMeta("facet_meta"),
    Facet_ConceptNode("facet__concept_node");
    // spotless:on

    private final String sqlName;

    DumpTable(String sqlName) {
        this.sqlName = sqlName;
    }

    public String getSqlName() {
        return sqlName;
    }
}
