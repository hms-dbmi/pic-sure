package edu.harvard.hms.dbmi.avillach.auth.model.response;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * One page of API key metadata, newest first.
 *
 * @param keys the keys on this page
 * @param totalCount how many keys match the listing in all
 * @param page the zero-based page number
 * @param size the page size that was applied
 */
@Schema(description = "One page of API key metadata, newest first. Every member is always present.")
public record ApiKeyPage(
    @Schema(description = "The keys on this page.", requiredMode = Schema.RequiredMode.REQUIRED) List<ApiKeyMetadata> keys,
    @Schema(
        description = "How many keys match the listing in all, across every page.", example = "42",
        requiredMode = Schema.RequiredMode.REQUIRED
    ) long totalCount,
    @Schema(description = "Zero-based number of this page.", example = "0", requiredMode = Schema.RequiredMode.REQUIRED) int page,
    @Schema(
        description = "Page size that was applied, after clamping to 1 through 1000.", example = "100",
        requiredMode = Schema.RequiredMode.REQUIRED
    ) int size
) {
}
