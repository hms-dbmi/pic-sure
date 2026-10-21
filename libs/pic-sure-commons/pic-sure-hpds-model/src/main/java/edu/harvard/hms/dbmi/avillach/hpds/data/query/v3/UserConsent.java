package edu.harvard.hms.dbmi.avillach.hpds.data.query.v3;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "One consent a query is scoped to, naming a study and consent group.")
public record UserConsent(
    @Schema(description = "The consent value, a study accession and consent group.", example = "phs000007.c1") String value
) {
}
