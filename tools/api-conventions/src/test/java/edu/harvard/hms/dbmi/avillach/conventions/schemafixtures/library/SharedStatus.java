package edu.harvard.hms.dbmi.avillach.conventions.schemafixtures.library;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * A documented model that stands for one compiled in a shared library.
 *
 * @param state the state
 */
@Schema(description = "The status of a load")
public record SharedStatus(@Schema(description = "The state of the load", example = "AVAILABLE") String state) {}
