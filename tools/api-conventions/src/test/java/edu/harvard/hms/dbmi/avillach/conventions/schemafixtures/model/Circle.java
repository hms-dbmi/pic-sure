package edu.harvard.hms.dbmi.avillach.conventions.schemafixtures.model;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * A documented subtype.
 *
 * @param radius the radius
 */
@Schema(description = "A circle")
public record Circle(@Schema(description = "The radius in pixels", example = "12.5") double radius) implements Shape {}
