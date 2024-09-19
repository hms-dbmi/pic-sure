package edu.harvard.dbmi.avillach.dictionary.concept.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import jakarta.annotation.Nullable;

import java.util.List;
import java.util.Map;
import java.util.Objects;


@JsonIgnoreProperties(ignoreUnknown = true)
// All this Json annotation stuff is doing is telling Jackson how to handle this polymorphic type. Essentially:
// - The types are defined by their name.
// - The name is set in the 'type' property
// - For each possible Concept type, here is what the 'type' property will be
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
    @JsonSubTypes.Type(value = ContinuousConcept.class, name = "Continuous"),
    @JsonSubTypes.Type(value = CategoricalConcept.class, name = "Categorical"),
})
public sealed interface Concept
    permits CategoricalConcept, ConceptShell, ContinuousConcept {

    /**
     * @return The complete concept path for this concept (// delimited)
     */
    String conceptPath();

    /**
     * @return The name, i.e. the right most concept in the concept path
     */
    String name();

    /**
     * @return The display name for end users
     */
    String display();


    /**
     * @return The unique name for the study / dataset that this concept belongs to
     */
    String dataset();

    /**
     * @return The type of this concept
     */
    ConceptType type();

    Concept table();

    Concept study();

    Map<String, String> meta();

    @Nullable
    List<Concept> children();

    Concept withChildren(List<Concept> children);

    Concept withTable(Concept table);

    Concept withStudy(Concept study);

    default boolean conceptEquals(Object object) {
        if (this == object) return true;
        if (!(object instanceof Concept)) return false;
        Concept that = (Concept) object;
        return Objects.equals(dataset(), that.dataset()) && Objects.equals(conceptPath(), that.conceptPath());
    }
}
