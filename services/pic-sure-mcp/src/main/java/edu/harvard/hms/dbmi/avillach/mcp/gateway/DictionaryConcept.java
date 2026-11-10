package edu.harvard.hms.dbmi.avillach.mcp.gateway;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;
import java.util.Map;

/**
 * One concept as the dictionary returns it. The dictionary serializes categorical and continuous concepts with a {@code type} discriminator
 * ({@code "Categorical"} or {@code "Continuous"}); this record carries the fields of both and leaves the ones that do not apply null.
 * Fields this service does not use, such as {@code children}, {@code table}, and {@code study}, are ignored.
 *
 * @param conceptPath the full concept path
 * @param name the last path segment
 * @param display the display name
 * @param dataset the dataset the concept belongs to
 * @param description the concept description
 * @param type the discriminator, {@code "Categorical"} or {@code "Continuous"}
 * @param values the categorical values, null for a continuous concept
 * @param min the continuous minimum, or null
 * @param max the continuous maximum, or null
 * @param studyAcronym the study acronym
 * @param meta the free-form metadata, or null
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DictionaryConcept(
    String conceptPath, String name, String display, String dataset, String description, String type, List<String> values, Double min,
    Double max, String studyAcronym, Map<String, String> meta
) {

    /** The {@code type} value of a categorical concept. */
    public static final String CATEGORICAL = "Categorical";

    /** The {@code type} value of a continuous concept. */
    public static final String CONTINUOUS = "Continuous";
}
