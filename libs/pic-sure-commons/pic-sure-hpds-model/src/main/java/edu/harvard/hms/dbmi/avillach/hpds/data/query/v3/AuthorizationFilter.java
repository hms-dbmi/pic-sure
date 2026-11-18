package edu.harvard.hms.dbmi.avillach.hpds.data.query.v3;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.Set;

@Schema(
    description = "A consent filter. On the authorized backend the server builds these from the caller's consents and replaces whatever the client sent. On the open backend the server adds none, and any the client sends are applied like phenotypic filters."
)
public record AuthorizationFilter(
    @Schema(description = "A concept path this filter must match.", example = "\\_consents\\") String conceptPath,
    @Schema(
        description = "Values for this concept path. Patients returned by this query must match at least one value for this concept path.",
        example = "[\"phs000007.c1\", \"phs000007.c2\"]"
    ) Set<String> values
) {

    /**
     * The concept path that holds each participant's study consent groups. An authorization filter on this path lists the consent groups
     * the caller may read, one value per group, for example {@code phs000007.c1}.
     */
    public static final String CONSENTS_CONCEPT_PATH = "\\_consents\\";

    /** The concept path that holds each participant's consent groups for genomic data. */
    public static final String TOPMED_CONSENTS_CONCEPT_PATH = "\\_topmed_consents\\";

    /** The concept path that holds each participant's consent groups for the harmonized data set. */
    public static final String HARMONIZED_CONSENT_CONCEPT_PATH = "\\_harmonized_consent\\";

    /**
     * Every concept path the server writes an authorization filter on. A filter on one of these paths lists consent groups, so its values
     * are consent values. {@code \_parent_consents\} is not one of them.
     */
    public static final Set<String> CONSENT_CONCEPT_PATHS =
        Set.of(CONSENTS_CONCEPT_PATH, TOPMED_CONSENTS_CONCEPT_PATH, HARMONIZED_CONSENT_CONCEPT_PATH);
}
