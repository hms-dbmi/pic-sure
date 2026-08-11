package edu.harvard.dbmi.avillach.contracts.auth;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.Map;
import java.util.Set;

/**
 * PSAMA's answer to {@code GET /user/me/consents}: the study authorizations of the caller behind the presented token.
 *
 * <p>This is the SOLE source of study-level access for v3 queries. Roles and privileges are endpoint-level only, so a client that wants to
 * know which studies a user may query reads this, not {@link IntrospectionResponse#roles()}.
 *
 * <p>A user with no stored consent record answers {@code {userId, consents: {}}} rather than an error: an empty map is a normal, expected
 * "nothing authorized", never a failure to retry. The persisted row's own uuid is deliberately NOT on the wire -- it is a storage detail of
 * PSAMA's {@code user_consents} table and no client has ever had a use for it.
 */
@Schema(description = "The study authorizations of the caller, keyed by concept path")
public record UserConsentsResponse(
    @Schema(description = "PSAMA's UUID for the user these consents belong to") String userId,
    @Schema(
        description = "Authorized consent identifiers, keyed by CONCEPT PATH -- not by study accession. The values are the consent "
            + "identifiers verbatim (\"phs000007.c1\", \"open_access-1000Genomes\"); clients match them against dictionary values and "
            + "put them in a v3 query's authorizationFilters as-is, never parsing or reformatting them. The producer is PSAMA's "
            + "BdcConsentsBuilder, and the keys it writes today are \"\\_consents\\\" (every authorized consent identifier plus every "
            + "public study), \"\\_harmonized_consent\\\" (the harmonized subset) and \"\\_topmed_consents\\\" (the subset whose study "
            + "dataType contains G). Those are the KNOWN keys, not the complete set: consumers must treat every key as optional and "
            + "tolerate ones they do not recognise. Empty when nothing is authorized."
    ) Map<String, Set<String>> consents
) {

    public UserConsentsResponse {
        consents = consents == null ? Map.of() : consents;
    }
}
