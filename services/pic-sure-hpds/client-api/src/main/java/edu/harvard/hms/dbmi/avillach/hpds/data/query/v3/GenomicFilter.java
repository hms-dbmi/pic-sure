package edu.harvard.hms.dbmi.avillach.hpds.data.query.v3;

import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.lang.NonNull;

import java.util.List;

public record GenomicFilter(
    @Schema(
        description = "The genomic filter to query", example = "Gene_with_variant", requiredMode = Schema.RequiredMode.REQUIRED
    ) String key,
    @Schema(
        description = "Values that must match for a given key. Cannot be combined with `min` or `max`", example = "APOE",
        requiredMode = Schema.RequiredMode.NOT_REQUIRED
    ) List<String> values,
    @Schema(
        description = "Minimum value for a given key. Cannot be combined with `values`", example = "0.5",
        requiredMode = Schema.RequiredMode.NOT_REQUIRED
    ) Float min,
    @Schema(
        description = "Maximum value for a given key. Cannot be combined with `values`", example = "100",
        requiredMode = Schema.RequiredMode.NOT_REQUIRED
    ) Float max
) {
}
