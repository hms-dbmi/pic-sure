package edu.harvard.hms.dbmi.avillach.operations.configuration;

import java.util.UUID;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Create/update request body for {@code /configuration/admin/**}. Mirrors {@code pic-sure-api-data}'s javax-validated
 * {@code ConfigurationRequest} verbatim, in the jakarta namespace, so this service stays self-contained.
 *
 * <p>No {@code @NotNull}: the legacy {@code ConfigurationRS} did manual null checks for {@code name}/{@code kind}/{@code value} on create
 * (reproduced in {@link ConfigurationController}), and PATCH treats every field as optional (partial update). {@code uuid} is accepted only
 * to honor the "UUID cannot be changed" guard on PATCH.
 */
public record ConfigurationRequestDto(
    UUID uuid, @Pattern(regexp = "^[\\w\\d\\-?\\[\\].():]+$") @Size(max = 255) String name,
    @Pattern(regexp = "^[\\w\\d\\-?\\[\\].():]+$") @Size(max = 255) String kind, String value, @Size(max = 255) String description,
    Boolean markForDelete
) {
}
