package edu.harvard.hms.dbmi.avillach.conventions.schemafixtures.model;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * A documented generic envelope. The payload is a type variable, which needs a description and no example.
 *
 * @param message a human-readable outcome
 * @param content the payload
 * @param <T> the payload type
 */
@Schema(description = "A response wrapped with a message")
public record Envelope<T>(
    @Schema(description = "A human-readable outcome", example = "2 studies loaded") String message,
    @Schema(description = "The payload") T content
) {}
