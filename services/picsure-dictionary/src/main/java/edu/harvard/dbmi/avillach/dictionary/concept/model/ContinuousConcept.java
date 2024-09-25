package edu.harvard.dbmi.avillach.dictionary.concept.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public record ContinuousConcept(
    String conceptPath, String name, String display, String dataset, String description, boolean allowFiltering,

    @Nullable Integer min, @Nullable Integer max,  String studyAcronym,
    Map<String, String> meta,
    @Nullable
    List<Concept> children,

    @Nullable
    Concept table,

    @Nullable
    Concept study
) implements Concept {

    public ContinuousConcept(
        String conceptPath, String name, String display, String dataset, String description, boolean allowFiltering,
        @Nullable Integer min, @Nullable Integer max,  String studyAcronym, Map<String, String> meta, @Nullable List<Concept> children
    ) {
        this(
            conceptPath, name, display, dataset, description, allowFiltering,
            min, max, studyAcronym, meta, children, null, null
        );
    }

    public ContinuousConcept(ContinuousConcept core, Map<String, String> meta) {
        this(
            core.conceptPath, core.name, core.display, core.dataset, core.description, core.allowFiltering,
            core.min, core.max, core.studyAcronym, meta, core.children
        );
    }

    public ContinuousConcept(String conceptPath, String dataset) {
        this(conceptPath, "", "", dataset, "", true, null, null, "", null, List.of());
    }

    public ContinuousConcept(
        String conceptPath, String name, String display, String dataset, String description, boolean allowFiltering,
        @Nullable Integer min, @Nullable Integer max,  String studyAcronym, Map<String, String> meta
    ) {
        this(conceptPath, name, display, dataset, description, allowFiltering, min, max, studyAcronym, meta, null);
    }

    @JsonProperty("type")
    @Override
    public ConceptType type() {
        return ConceptType.Continuous;
    }

    @Override
    public ContinuousConcept withChildren(List<Concept> children) {
        return new ContinuousConcept(
            conceptPath, name, display, dataset, description, allowFiltering, min, max, studyAcronym, meta, children
        );
    }

    @Override
    public Concept withTable(Concept table) {
        return new ContinuousConcept(
            conceptPath, name, display, dataset, description, allowFiltering,
            min, max, studyAcronym, meta, children, table, study
        );
    }

    @Override
    public Concept withStudy(Concept study) {
        return new ContinuousConcept(
            conceptPath, name, display, dataset, description, allowFiltering,
            min, max, studyAcronym, meta, children, table, study
        );
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
