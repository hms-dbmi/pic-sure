package edu.harvard.dbmi.avillach.dictionary.concept.model;

import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.util.StringUtils;

@Schema(description = "Whether a concept's values are categories or numbers.")
public enum ConceptType {
    /**
     * i.e. Eye color: brown, blue, hazel, etc.
     */
    @Schema(description = "The value is one of a fixed set of categories, such as sex or a diagnosis code.")
    Categorical,

    /**
     * i.e. Age: 0 - 150 Also known as numeric (to me)
     */
    @Schema(description = "The value is a number within a range, such as age.")
    Continuous;

    public static ConceptType toConcept(String in) {
        return switch (StringUtils.capitalize(in)) {
            case "Continuous" -> Continuous;
            default -> Categorical;
        };
    }

}
