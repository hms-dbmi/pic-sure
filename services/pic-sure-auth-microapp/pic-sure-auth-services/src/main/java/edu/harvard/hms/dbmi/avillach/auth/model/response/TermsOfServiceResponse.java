package edu.harvard.hms.dbmi.avillach.auth.model.response;

import edu.harvard.hms.dbmi.avillach.auth.entity.TermsOfService;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.Date;
import java.util.UUID;

/**
 * The body of {@code POST /tos/update}: the terms of service row the update stored. Every member is always written.
 *
 * @param uuid the row identifier
 * @param content the terms as HTML
 * @param dateUpdated when the row was stored, in milliseconds since the Unix epoch
 */
@Schema(description = "A stored terms of service version. Every member is always present.")
public record TermsOfServiceResponse(
    @Schema(
        description = "Row identifier of the terms of service version.", example = "8694e3d4-5cb4-410f-8431-993445e6d3f6",
        requiredMode = Schema.RequiredMode.REQUIRED
    ) UUID uuid,
    @Schema(
        description = "The terms of service as HTML.", example = "<h1>Terms of Service</h1><p>Use of this system is monitored.</p>",
        requiredMode = Schema.RequiredMode.REQUIRED
    ) String content,
    @Schema(
        description = "When this version was stored, in milliseconds since the Unix epoch.", implementation = Long.class,
        example = "1790777100000", requiredMode = Schema.RequiredMode.REQUIRED
    ) Date dateUpdated
) {

    /**
     * Copies a stored terms of service row into its response shape.
     *
     * @param termsOfService the stored row
     * @return the response record
     */
    public static TermsOfServiceResponse from(TermsOfService termsOfService) {
        return new TermsOfServiceResponse(termsOfService.getUuid(), termsOfService.getContent(), termsOfService.getDateUpdated());
    }
}
