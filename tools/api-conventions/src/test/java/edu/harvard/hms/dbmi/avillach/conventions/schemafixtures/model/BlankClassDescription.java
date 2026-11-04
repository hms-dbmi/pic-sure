package edu.harvard.hms.dbmi.avillach.conventions.schemafixtures.model;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * A record whose class-level description is blank.
 *
 * @param accession the accession
 */
@Schema(description = "  ")
public record BlankClassDescription(@Schema(description = "The study accession", example = "phs000007") String accession) {}
