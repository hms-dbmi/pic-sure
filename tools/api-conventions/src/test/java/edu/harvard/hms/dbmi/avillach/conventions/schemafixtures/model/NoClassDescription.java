package edu.harvard.hms.dbmi.avillach.conventions.schemafixtures.model;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * A record whose components are documented and whose type is not.
 *
 * @param accession the accession
 */
public record NoClassDescription(@Schema(description = "The study accession", example = "phs000007") String accession) {}
