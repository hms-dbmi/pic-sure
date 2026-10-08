package edu.harvard.dbmi.avillach.dictionary.concept.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import edu.harvard.dbmi.avillach.dictionary.dataset.Dataset;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.annotation.Nullable;

import java.util.List;
import java.util.Map;
import java.util.Objects;

@Schema(description = "A concept whose value is one of a fixed set of categories, such as sex or a diagnosis code.")
public record CategoricalConcept(
    @Schema(
        description = "Full path of the concept, backslash delimited. Together with dataset it identifies the concept.",
        example = "\\demographics\\SEX\\", requiredMode = Schema.RequiredMode.REQUIRED
    ) String conceptPath,

    @Schema(
        description = "Last segment of the concept path, the variable's own identifier.", example = "SEX",
        requiredMode = Schema.RequiredMode.REQUIRED
    ) String name,

    @Schema(
        description = "Name of the concept shown to users.", example = "Sex", requiredMode = Schema.RequiredMode.REQUIRED
    ) String display,

    @Schema(
        description = "Ref of the dataset the concept belongs to. For a dbGaP study this is its accession.", example = "phs000007",
        requiredMode = Schema.RequiredMode.REQUIRED
    ) String dataset,

    @Schema(
        description = "Longer text describing the variable. Null when the dictionary holds none.", example = "Sex of the participant"
    ) String description,

    @Schema(
        description = "The categories the variable takes. Empty when none are recorded, null on the bulk detail endpoint.",
        example = "[\"Male\", \"Female\"]"
    ) List<String> values,

    @Schema(
        description = "False when the concept must not be offered as a query filter, for example a stigmatizing variable or an ancestor returned by the hierarchy endpoint.",
        requiredMode = Schema.RequiredMode.REQUIRED
    ) boolean allowFiltering,

    @Schema(description = "Abbreviation of the dataset the concept belongs to.", example = "FHS") String studyAcronym,

    @Schema(
        description = "Child concepts. The tree endpoints fill this down to the requested depth. Elsewhere it is null or empty."
    ) @Nullable List<Concept> children,

    @Schema(
        description = "Concept metadata from the dictionary. The keys are the metadata keys loaded for this concept, such as description and values, and vary by dataset. The bulk detail and dump endpoints rewrite each key as capitalized words, so study_id becomes Study Id. Null on the search and tree endpoints."
    ) @Nullable Map<String, String> meta,

    @Schema(
        description = "The concept's table, which is the concept at the first two segments of its path. Set only by the single-concept detail endpoint when extra details are enabled. Null elsewhere."
    ) @Nullable Concept table,

    @Schema(
        description = "The dataset the concept belongs to, with its metadata. Set only by the single-concept detail endpoint when extra details are enabled. Null elsewhere."
    ) @Nullable Dataset study

) implements Concept {

    public CategoricalConcept(
        String conceptPath, String name, String display, String dataset, String description, List<String> values, boolean allowFiltering,
        String studyAcronym, @Nullable List<Concept> children, @Nullable Map<String, String> meta
    ) {
        this(conceptPath, name, display, dataset, description, values, allowFiltering, studyAcronym, children, meta, null, null);
    }

    public CategoricalConcept(CategoricalConcept core, Map<String, String> meta) {
        this(
            core.conceptPath, core.name, core.display, core.dataset, core.description, core.values, core.allowFiltering, core.studyAcronym,
            core.children, meta
        );
    }

    public CategoricalConcept(String conceptPath, String dataset) {
        this(conceptPath, "", "", dataset, "", List.of(), false, "", List.of(), null);
    }


    @Schema(description = "Which kind of concept this is. Always Categorical for this shape.", requiredMode = Schema.RequiredMode.REQUIRED)
    @JsonProperty("type")
    @Override
    public ConceptType type() {
        return ConceptType.Categorical;
    }

    @Override
    public CategoricalConcept withChildren(List<Concept> children) {
        return new CategoricalConcept(
            conceptPath, name, display, dataset, description, values, allowFiltering, studyAcronym, children, meta
        );
    }

    @Override
    public Concept withTable(Concept table) {
        return new CategoricalConcept(
            conceptPath, name, display, dataset, description, values, allowFiltering, studyAcronym, children, meta, table, study
        );
    }

    @Override
    public Concept withStudy(Dataset study) {
        return new CategoricalConcept(
            conceptPath, name, display, dataset, description, values, allowFiltering, studyAcronym, children, meta, table, study
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
