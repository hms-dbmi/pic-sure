package edu.harvard.hms.dbmi.avillach.operations.configuration;

import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Public JSON shape for a {@code Configuration}. Kept separate from the {@code pic-sure-api-data} JPA entity so the persistence model never
 * leaks directly onto the wire.
 */
@Schema(description = "A site configuration entry: one named value of one kind that the UI reads.")
public record ConfigurationDto(
    @Schema(
        description = "The id of the entry.", example = "8694e3d4-5cb4-410f-8431-993445e6d3f6", requiredMode = Schema.RequiredMode.REQUIRED
    ) UUID uuid,
    @Schema(
        description = "The name of the entry, unique within its kind.", example = "enableGENEQuery",
        requiredMode = Schema.RequiredMode.REQUIRED
    ) String name,
    @Schema(
        description = "The group the entry belongs to. The list endpoint filters on it.", example = "features",
        requiredMode = Schema.RequiredMode.REQUIRED
    ) String kind,
    @Schema(
        description = "The value of the entry as text. The reader decides how to parse it.", example = "true",
        requiredMode = Schema.RequiredMode.REQUIRED
    ) String value,
    @Schema(
        description = "What the entry controls, for administrators. May be null or empty.",
        example = "Turns on the gene filter in the query builder."
    ) String description,
    @Schema(description = "Whether the entry is flagged for deletion.", requiredMode = Schema.RequiredMode.REQUIRED) Boolean markForDelete
) {
}
