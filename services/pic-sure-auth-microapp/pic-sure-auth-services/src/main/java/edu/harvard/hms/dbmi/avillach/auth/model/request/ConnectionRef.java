package edu.harvard.hms.dbmi.avillach.auth.model.request;

import jakarta.validation.constraints.NotBlank;

/**
 * A reference to an existing {@code Connection} by its business {@code id} (for example {@code "fence"}), matching what {@code UserService}
 * and {@code UserMetadataMappingService} have always looked connections up by.
 */
public record ConnectionRef(@NotBlank String id) {
}
