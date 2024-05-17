package edu.harvard.dbmi.avillach.dictionary.concept.model;

import java.util.List;
import java.util.Map;

public record CategoricalConcept(
    String conceptPath, String name, String display, String dataset,

    List<String> values,

    Map<String, String> meta

) implements Concept {


    @Override
    public ConceptType type() {
        return ConceptType.Categorical;
    }
}
