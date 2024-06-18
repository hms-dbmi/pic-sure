package edu.harvard.dbmi.avillach.dictionary.concept.model;

import org.springframework.util.StringUtils;

public enum ConceptType {
    /**
     * i.e. Eye color: brown, blue, hazel, etc.
     */
    Categorical,

    /**
     * i.e. Age: 0 - 150
     * Also known as numeric (to me)
     */
    Continuous;

    public static ConceptType toConcept(String in) {
        return switch (StringUtils.capitalize(in)) {
            case "Continuous" -> Continuous;
            default -> Categorical;
        };
    }

}
