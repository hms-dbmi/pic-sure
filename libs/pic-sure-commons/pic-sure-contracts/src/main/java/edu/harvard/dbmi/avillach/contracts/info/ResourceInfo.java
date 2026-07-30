package edu.harvard.dbmi.avillach.contracts.info;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.UUID;

public record ResourceInfo(
    @Schema(description = "PIC-SURE id of this resource") UUID id, @Schema(description = "Display name of this resource") String name,
    @Schema(description = "Query formats this resource accepts") List<QueryFormat> queryFormats
) {

    public ResourceInfo {
        queryFormats = queryFormats == null ? List.of() : queryFormats;
    }
}
