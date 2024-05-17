package edu.harvard.dbmi.avillach.dictionary.concept.model;

public enum ConceptType {
    /**
     * i.e. Eye color: brown, blue, hazel, etc.
     */
    Categorical,

    /**
     * i.e. Age: 0 - 150
     * Also known as numeric (to me)
     */
    Continuous,

    /**
     * A catch-all concept
     */
    FreeText,

    /**
     * In an ontology of concepts, all interior nodes will be this type
     */
    Interior,

}
