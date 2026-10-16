package edu.harvard.hms.dbmi.avillach.conventions.schemafixtures.model;

import io.swagger.v3.oas.annotations.media.Schema;

/** A fully documented enum. Its static constant and its instance field are not constants and are not read. */
@Schema(description = "The kind of data a study holds")
public enum StudyKind {

    @Schema(description = "Phenotypic observations only")
    PHENOTYPIC("p"),

    @Schema(description = "Phenotypic observations and genomic variants")
    GENOMIC("g");

    public static final String DEFAULT_CODE = "p";

    private final String code;

    StudyKind(String code) {
        this.code = code;
    }

    /** @return the one-letter code */
    public String code() {
        return code;
    }
}
