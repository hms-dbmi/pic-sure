package edu.harvard.hms.dbmi.avillach.conventions.schemafixtures.library;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * A model in the shared library with an example missing.
 *
 * @param state the state
 */
@Schema(description = "A status with an undocumented member")
public record SharedBroken(@Schema(description = "The state of the load") String state) {}
