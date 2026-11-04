package edu.harvard.hms.dbmi.avillach.conventions.schemafixtures.model;

import io.swagger.v3.oas.annotations.media.Schema;

/** A documented class with an undocumented field, an undocumented enum member and an undocumented superclass. */
@Schema(description = "A class that predates the convention")
public class LegacyDto extends UndocumentedBase {

    private String label;

    @Schema(description = "The state")
    private UndocumentedEnum state;

    /** @return the label */
    public String getLabel() {
        return label;
    }

    /** @return the state */
    public UndocumentedEnum getState() {
        return state;
    }
}
