package edu.harvard.hms.dbmi.avillach.mcp.tool;

import com.fasterxml.jackson.annotation.JsonInclude;
import edu.harvard.hms.dbmi.avillach.mcp.gateway.DictionaryConcept;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;

/**
 * A concept trimmed for the model: no children, no table or study, and metadata only when a detail lookup asks for it. Fields that do not
 * apply are omitted from the JSON, since Spring AI rejects a null in {@code structuredContent}.
 *
 * @param conceptPath the full concept path, the identifier the count tools take
 * @param display the display name
 * @param dataset the dataset the concept belongs to
 * @param type {@code categorical} or {@code continuous}
 * @param studyAcronym the study acronym
 * @param name the last path segment, set on detail lookups
 * @param description the concept description
 * @param values the categorical values, at most {@link #MAX_VALUES}
 * @param valuesOmitted how many categorical values were dropped by the cap
 * @param min the continuous minimum
 * @param max the continuous maximum
 * @param meta a few metadata entries, set on detail lookups
 * @param matchedTerms the terms of a several-term search that returned this concept, in the order they were given
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ConceptSummary(
    String conceptPath, String display, String dataset, String type, @Schema(requiredMode = NOT_REQUIRED) String studyAcronym,
    @Schema(requiredMode = NOT_REQUIRED) String name, @Schema(requiredMode = NOT_REQUIRED) String description,
    @Schema(requiredMode = NOT_REQUIRED) List<String> values, @Schema(requiredMode = NOT_REQUIRED) Integer valuesOmitted,
    @Schema(requiredMode = NOT_REQUIRED) Double min, @Schema(requiredMode = NOT_REQUIRED) Double max,
    @Schema(requiredMode = NOT_REQUIRED) Map<String, String> meta, @Schema(requiredMode = NOT_REQUIRED) List<String> matchedTerms
) {

    /** The most categorical values a result carries. */
    public static final int MAX_VALUES = 20;

    /** The most metadata entries a detail result carries. */
    public static final int MAX_META_ENTRIES = 10;

    /** The longest metadata value a detail result carries, in characters. */
    public static final int MAX_META_VALUE_LENGTH = 300;

    /**
     * Trims a search result concept.
     *
     * @param concept the dictionary's concept
     * @return the summary, without name or metadata
     */
    public static ConceptSummary summary(DictionaryConcept concept) {
        return of(concept, false);
    }

    /**
     * Trims a detail lookup concept, keeping the name and a few metadata entries.
     *
     * @param concept the dictionary's concept
     * @return the summary with name and capped metadata
     */
    public static ConceptSummary detail(DictionaryConcept concept) {
        return of(concept, true);
    }

    /**
     * Copies this summary with the terms that returned it.
     *
     * @param terms the terms of a several-term search that returned this concept
     * @return the copy
     */
    public ConceptSummary withMatchedTerms(List<String> terms) {
        return new ConceptSummary(
            conceptPath, display, dataset, type, studyAcronym, name, description, values, valuesOmitted, min, max, meta, List.copyOf(terms)
        );
    }

    private static ConceptSummary of(DictionaryConcept concept, boolean detail) {
        List<String> values = concept.values();
        List<String> kept = values == null ? null : values.stream().limit(MAX_VALUES).toList();
        Integer omitted = values != null && values.size() > MAX_VALUES ? values.size() - MAX_VALUES : null;
        String type = concept.type() == null ? null : concept.type().toLowerCase(Locale.ROOT);
        return new ConceptSummary(
            concept.conceptPath(), concept.display(), concept.dataset(), type, concept.studyAcronym(), detail ? concept.name() : null,
            concept.description(), kept, omitted, concept.min(), concept.max(), detail ? cappedMeta(concept.meta()) : null, null
        );
    }

    private static Map<String, String> cappedMeta(Map<String, String> meta) {
        if (meta == null || meta.isEmpty()) {
            return null;
        }
        Map<String, String> capped = new LinkedHashMap<>();
        meta.entrySet().stream().filter(e -> e.getKey() != null && e.getValue() != null).limit(MAX_META_ENTRIES).forEach(
            e -> capped.put(
                e.getKey(), e.getValue().length() > MAX_META_VALUE_LENGTH ? e.getValue().substring(0, MAX_META_VALUE_LENGTH) : e.getValue()
            )
        );
        return capped.isEmpty() ? null : capped;
    }
}
