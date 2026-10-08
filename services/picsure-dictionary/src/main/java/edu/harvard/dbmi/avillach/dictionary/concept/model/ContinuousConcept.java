package edu.harvard.dbmi.avillach.dictionary.concept.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import edu.harvard.dbmi.avillach.dictionary.dataset.Dataset;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.annotation.Nullable;

import java.util.List;
import java.util.Map;
import java.util.Objects;

@Schema(description = "A concept whose value is a number within a range, such as age.")
public record ContinuousConcept(
    @Schema(
        description = "Full path of the concept, backslash delimited. Together with dataset it identifies the concept.",
        example = "\\demographics\\AGE\\", requiredMode = Schema.RequiredMode.REQUIRED
    ) String conceptPath,

    @Schema(
        description = "Last segment of the concept path, the variable's own identifier.", example = "AGE",
        requiredMode = Schema.RequiredMode.REQUIRED
    ) String name,

    @Schema(
        description = "Name of the concept shown to users.", example = "Age", requiredMode = Schema.RequiredMode.REQUIRED
    ) String display,

    @Schema(
        description = "Ref of the dataset the concept belongs to. For a dbGaP study this is its accession.", example = "phs000007",
        requiredMode = Schema.RequiredMode.REQUIRED
    ) String dataset,

    @Schema(
        description = "Longer text describing the variable. Null when the dictionary holds none.",
        example = "Age of the participant at enrollment, in years"
    ) String description,

    @Schema(
        description = "False when the concept must not be offered as a query filter, for example a stigmatizing variable or an ancestor returned by the hierarchy endpoint.",
        requiredMode = Schema.RequiredMode.REQUIRED
    ) boolean allowFiltering,

    @Schema(
        description = "Smallest value recorded for the variable. Null on the bulk detail endpoint, 0 when the dictionary holds no range.",
        example = "18.0"
    ) @Nullable Double min,

    @Schema(
        description = "Largest value recorded for the variable. Null on the bulk detail endpoint, 0 when the dictionary holds no range.",
        example = "89.0"
    ) @Nullable Double max,

    @Schema(description = "Abbreviation of the dataset the concept belongs to.", example = "FHS") String studyAcronym,

    @Schema(
        description = "Concept metadata from the dictionary. The keys are the metadata keys loaded for this concept, such as description and values, and vary by dataset. The bulk detail and dump endpoints rewrite each key as capitalized words, so study_id becomes Study Id. Null on the search and tree endpoints."
    ) Map<String, String> meta,

    @Schema(
        description = "Child concepts. The tree endpoints fill this down to the requested depth. Elsewhere it is null or empty."
    ) @Nullable List<Concept> children,

    @Schema(
        description = "The concept's table, which is the concept at the first two segments of its path. Set only by the single-concept detail endpoint when extra details are enabled. Null elsewhere."
    ) @Nullable Concept table,

    @Schema(
        description = "The dataset the concept belongs to, with its metadata. Set only by the single-concept detail endpoint when extra details are enabled. Null elsewhere."
    ) @Nullable Dataset study
) implements Concept {

    public ContinuousConcept(
        String conceptPath, String name, String display, String dataset, String description, boolean allowFiltering, @Nullable Double min,
        @Nullable Double max, String studyAcronym, Map<String, String> meta, @Nullable List<Concept> children
    ) {
        this(conceptPath, name, display, dataset, description, allowFiltering, min, max, studyAcronym, meta, children, null, null);
    }

    public ContinuousConcept(ContinuousConcept core, Map<String, String> meta) {
        this(
            core.conceptPath, core.name, core.display, core.dataset, core.description, core.allowFiltering, core.min, core.max,
            core.studyAcronym, meta, core.children
        );
    }

    public ContinuousConcept(String conceptPath, String dataset) {
        this(conceptPath, "", "", dataset, "", true, null, null, "", null, List.of());
    }

    public ContinuousConcept(
        String conceptPath, String name, String display, String dataset, String description, boolean allowFiltering, @Nullable Double min,
        @Nullable Double max, String studyAcronym, Map<String, String> meta
    ) {
        this(conceptPath, name, display, dataset, description, allowFiltering, min, max, studyAcronym, meta, null);
    }

    @Schema(description = "Which kind of concept this is. Always Continuous for this shape.", requiredMode = Schema.RequiredMode.REQUIRED)
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
            conceptPath, name, display, dataset, description, allowFiltering, min, max, studyAcronym, meta, children, table, study
        );
    }

    @Override
    public Concept withStudy(Dataset study) {
        return new ContinuousConcept(
            conceptPath, name, display, dataset, description, allowFiltering, min, max, studyAcronym, meta, children, table, study
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
