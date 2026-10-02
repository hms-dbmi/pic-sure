package edu.harvard.dbmi.avillach.dictionary.concept.model;

import edu.harvard.dbmi.avillach.dictionary.dataset.Dataset;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.Map;
import java.util.Objects;

@Schema(
    description = "A placeholder the service uses internally when it knows only a concept's path and dataset. Jackson never writes it: the concept services throw before one reaches a response, and it is not a named subtype of Concept."
)
public record ConceptShell(
    @Schema(
        description = "Full path of the concept, backslash delimited.", example = "\\demographics\\AGE\\",
        requiredMode = Schema.RequiredMode.REQUIRED
    ) String conceptPath,
    @Schema(
        description = "Ref of the dataset the concept belongs to.", example = "phs000007", requiredMode = Schema.RequiredMode.REQUIRED
    ) String dataset
) implements Concept {
    @Override
    public String name() {
        return "Shell. Not for external use.";
    }

    @Override
    public String display() {
        return "Shell. Not for external use.";
    }

    @Override
    public String studyAcronym() {
        return "Shell. Not for external use.";
    }

    @Override
    public ConceptType type() {
        return ConceptType.Continuous;
    }

    @Override
    public Concept table() {
        return null;
    }

    @Override
    public Dataset study() {
        return null;
    }

    @Override
    public Map<String, String> meta() {
        return Map.of();
    }

    @Override
    public List<Concept> children() {
        return List.of();
    }

    @Override
    public ConceptShell withChildren(List<Concept> children) {
        return this;
    }

    @Override
    public Concept withTable(Concept table) {
        return this;
    }

    @Override
    public Concept withStudy(Dataset study) {
        return this;
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
