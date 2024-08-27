package edu.harvard.dbmi.avillach.dictionary.concept.model;

import java.util.Map;
import java.util.Objects;

public record ConceptShell(String conceptPath, String dataset) implements Concept {
    @Override
    public String name() {
        return "Shell. Not for external use.";
    }

    @Override
    public String display() {
        return "Shell. Not for external use.";
    }

    @Override
    public ConceptType type() {
        return ConceptType.Continuous;
    }

    @Override
    public Map<String, String> meta() {
        return Map.of();
    }

    @Override
    public boolean equals(Object object) {
        return conceptEquals(object);
    }

    @Override
    public int hashCode() {
        return Objects.hash(conceptPath, dataset);
    }
}
