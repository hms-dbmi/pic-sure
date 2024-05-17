package edu.harvard.dbmi.avillach.dictionary.concept.model;

import java.util.Map;

public record FreeTextConcept(
    String conceptPath, String name, String display, String dataset,
    Map<String, String> meta

) implements Concept {
    @Override
    public ConceptType type() {
        return ConceptType.FreeText;
    }
}
