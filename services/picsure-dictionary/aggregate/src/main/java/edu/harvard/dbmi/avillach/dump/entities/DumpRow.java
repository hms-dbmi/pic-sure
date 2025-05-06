package edu.harvard.dbmi.avillach.dump.entities;

public sealed interface DumpRow permits ConceptNodeDump, FacetDump, FacetCategoryDump, ConceptNodeMetaDump, FacetCategoryMetaDump, FacetMetaDump, FacetConceptPair {
}
