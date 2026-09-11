package edu.harvard.hms.dbmi.avillach.operations.configuration;

import java.util.UUID;

/**
 * Public JSON shape for a {@code Configuration}. Kept separate from the {@code pic-sure-api-data} JPA entity so the persistence model never
 * leaks directly onto the wire.
 */
public record ConfigurationDto(UUID uuid, String name, String kind, String value, String description, Boolean markForDelete) {
}
