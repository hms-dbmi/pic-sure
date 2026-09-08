package edu.harvard.hms.dbmi.avillach.operations.configuration;

import java.util.UUID;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Create and update request body for {@code /configuration/admin/**}.
 *
 * <p>There is no {@code @NotNull} because {@link ConfigurationController} performs create-time null checks while PATCH treats every field
 * as optional. {@code uuid} is accepted only to enforce the "UUID cannot be changed" guard on PATCH.
 */
public record ConfigurationRequestDto(
    UUID uuid, @Pattern(regexp = "^[\\w\\d\\-?\\[\\].():]+$") @Size(max = 255) String name,
    @Pattern(regexp = "^[\\w\\d\\-?\\[\\].():]+$") @Size(max = 255) String kind, String value, @Size(max = 255) String description,
    Boolean markForDelete
) {
}
