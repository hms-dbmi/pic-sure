package edu.harvard.hms.dbmi.avillach.conventions.schemafixtures.model;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Date;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * One described member per scalar kind, none with an example.
 *
 * @param name a string
 * @param count a primitive
 * @param total a boxed number
 * @param id a UUID
 * @param created an instant
 * @param updated a date
 * @param released a local date
 * @param blankExample a string whose example is blank
 */
@Schema(description = "A model whose scalars carry no example")
public record MissingScalarExamples(
    @Schema(description = "A name") String name,
    @Schema(description = "A count") int count,
    @Schema(description = "A total") Long total,
    @Schema(description = "An identifier") UUID id,
    @Schema(description = "A creation time") Instant created,
    @Schema(description = "An update time") Date updated,
    @Schema(description = "A release day") LocalDate released,
    @Schema(description = "A name with a blank example", example = " ") String blankExample
) {}
