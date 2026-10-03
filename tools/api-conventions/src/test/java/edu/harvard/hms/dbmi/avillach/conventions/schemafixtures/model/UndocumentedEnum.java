package edu.harvard.hms.dbmi.avillach.conventions.schemafixtures.model;

import io.swagger.v3.oas.annotations.media.Schema;

/** An enum with no description on the type, one documented constant, one bare constant and one blank one. */
public enum UndocumentedEnum {

    @Schema(description = "Documented")
    DOCUMENTED,

    BARE,

    @Schema(description = "")
    BLANK
}
