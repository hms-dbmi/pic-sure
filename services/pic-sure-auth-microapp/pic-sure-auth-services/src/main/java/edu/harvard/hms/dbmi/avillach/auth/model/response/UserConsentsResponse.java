package edu.harvard.hms.dbmi.avillach.auth.model.response;

import edu.harvard.hms.dbmi.avillach.auth.entity.UserConsents;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The body of {@code GET /user/me/consents}: the studies the caller may query, keyed by consent concept path. Every member is always
 * written; a user with no stored consents gets a {@code null} {@code uuid} and an empty {@code consents} map.
 *
 * @param uuid the consents row identifier, or {@code null} when the user has no row
 * @param userId the user's row identifier
 * @param consents the study identifiers granted under each consent concept path
 */
@Schema(
    description = "The studies the caller may query, keyed by consent concept path. Every member is always present; a user with no "
        + "stored consents has a null uuid and an empty consents map."
)
public record UserConsentsResponse(
    @Schema(
        description = "Row identifier of the consents record. Null when the user has no stored consents.",
        example = "8694e3d4-5cb4-410f-8431-993445e6d3f6", requiredMode = Schema.RequiredMode.REQUIRED
    ) UUID uuid,
    @Schema(
        description = "Row identifier of the user.", example = "8694e3d4-5cb4-410f-8431-993445e6d3f6",
        requiredMode = Schema.RequiredMode.REQUIRED
    ) UUID userId,
    @Schema(
        description = "Study identifiers the caller may query, keyed by consent concept path. The clients read the \\_consents\\ key, "
            + "whose values are consent identifiers such as phs000007.c1. Empty when the user has no stored consents.",
        requiredMode = Schema.RequiredMode.REQUIRED
    ) Map<String, Set<String>> consents
) {

    /**
     * Copies a consents record into its response shape.
     *
     * @param userConsents the stored record, or the empty one the service builds for a user without a row
     * @return the response record
     */
    public static UserConsentsResponse from(UserConsents userConsents) {
        return new UserConsentsResponse(userConsents.getUuid(), userConsents.getUserId(), userConsents.getConsents());
    }
}
