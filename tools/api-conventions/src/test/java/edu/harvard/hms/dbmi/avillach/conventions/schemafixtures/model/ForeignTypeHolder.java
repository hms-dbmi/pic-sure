package edu.harvard.hms.dbmi.avillach.conventions.schemafixtures.model;

import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * A model with a member whose type no checked module compiles, so nothing can read its documentation.
 *
 * @param location where a parameter sits
 */
@Schema(description = "A model that leaks a third-party type")
public record ForeignTypeHolder(@Schema(description = "Where the parameter sits") ParameterIn location) {}
