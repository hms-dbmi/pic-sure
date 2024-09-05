package edu.harvard.dbmi.avillach.dictionary.concept.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public record ContinuousConcept(
    String conceptPath, String name, String display, String dataset, String description,

    @Nullable Integer min, @Nullable Integer max,
    Map<String, String> meta,
    @Nullable
    List<Concept> children
) implements Concept {

    public ContinuousConcept(ContinuousConcept core, Map<String, String> meta) {
        this(core.conceptPath, core.name, core.display, core.dataset, core.description, core.min, core.max, meta, core.children);
    }

    public ContinuousConcept(ContinuousConcept core, List<Concept> children) {
        this(core.conceptPath, core.name, core.display, core.dataset, core.description, core.min, core.max, core.meta, children);
    }

    public ContinuousConcept(String conceptPath, String dataset) {
        this(conceptPath, "", "", dataset, "", null, null, null, List.of());
    }

    public ContinuousConcept(
        String conceptPath, String name, String display, String dataset, String description,
        @Nullable Integer min, @Nullable Integer max, Map<String, String> meta
    ) {
        this(conceptPath, name, display, dataset, description, min, max, meta, null);
    }

    @JsonProperty("type")
    @Override
    public ConceptType type() {
        return ConceptType.Continuous;
    }

    @Override
    public ContinuousConcept withChildren(List<Concept> children) {
        return new ContinuousConcept(this, children);
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
