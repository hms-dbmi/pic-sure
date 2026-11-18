package edu.harvard.dbmi.avillach.domain;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Request body for {@code POST /internal/queries} on the operations service. The query service sends it to persist a query it has just
 * dispatched to HPDS. Every component is optional: a synchronous query is stored with no status and no metadata. The query service and the
 * operations service exchange it, and no client reads it.
 *
 * <p>{@code status} travels as the {@link PicSureStatus} constant name and {@code metadata} as base64 text, so the wire carries plain
 * strings and the operations service answers 400 for a name or an encoding it cannot read.
 *
 * <p>{@code picsureId}, {@code userUuid}, {@code consents}, {@code trigger} and {@code queryToVariantUuid} travel together. A request
 * carries all five, with {@code consents} possibly an empty map, or none of them, and the operations service answers 400 for any other mix.
 * A request with none is stored as a new row under a random id with no owner and no general query.
 *
 * <p>A request with all five is an upsert: the first one inserts the row under that id with its owner, and a later one updates the
 * consents, status and metadata of that row and never its body, version or owner. Each one records one run of the query. A later one that
 * names a different owner than the stored row has is answered with 409 and changes nothing.
 *
 * <p>{@code queryToVariantUuid} names the general query of the row: the caller's query whatever its result type and whether or not it is a
 * sample. {@code sample} and {@code resultType} say which variant of that general query the row is. The request that inserts the row
 * creates the general query when it is not stored yet and writes all three onto the row. A later request for the same row never changes
 * them. A request whose general query is stored under a different owner is answered with 409 and stores nothing.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@Schema(description = "A query the query service asks the operations service to persist.")
public record SaveQueryRequest(
    @Schema(
        description = "The query request as a JSON string, stored as sent except that a top-level resourceCredentials member is removed.",
        example = "{\"query\":{\"expectedResultType\":\"COUNT\"}}"
    ) String query,
    @Schema(
        description = "The id HPDS assigned to the result, when it assigned one.", example = "8694e3d4-5cb4-410f-8431-993445e6d3f6"
    ) String resourceResultId,
    @Schema(
        description = "The query status as a PicSureStatus constant name. An unknown name is answered with 400.", example = "PENDING"
    ) String status,
    @Schema(
        description = "The query format version. 3 marks a v3 query and an absent value marks a query stored before v3.", example = "3"
    ) String version,
    @Schema(
        description = "Result metadata as base64-encoded UTF-8 JSON. Text that is not base64 is answered with 400.",
        example = "eyJwaWNzdXJlUXVlcnlJZCI6Ijg2OTRlM2Q0LTVjYjQtNDEwZi04NDMxLTk5MzQ0NWU2ZDNmNiJ9"
    ) String metadata,
    @Schema(
        description = "The id to store the row under. A request without one is stored under a random id.",
        example = "8694e3d4-5cb4-410f-8431-993445e6d3f6"
    ) UUID picsureId,
    @Schema(
        description = "The UUID of the user who owns the row. It is written when the row is inserted and never changed afterward.",
        example = "5f0c1f0e-6f0a-4a57-9d6e-0d1c6a3b9f11"
    ) UUID userUuid,
    @Schema(
        description = "The consents the query was scoped with, as consent concept path to consent values.",
        example = "{\"\\\\_consents\\\\\":[\"phs000001.c1\"]}"
    ) Map<String, List<String>> consents,
    @Schema(
        description = "Why the query was submitted to HPDS: FIRST_SUBMIT, RESUBMIT, CONSENT_CHANGE or LOST_RESULT. When present, one run is recorded. An unknown name is answered with 400.",
        example = "FIRST_SUBMIT"
    ) String trigger,
    @Schema(
        description = "The id of the general query the row is a variant of. It is written when the row is inserted and never changed afterward. A request that names an owner must carry one, and a request without an owner must not.",
        example = "3d2f7c1a-9b4e-5c6d-8e7f-0a1b2c3d4e5f"
    ) UUID queryToVariantUuid,
    @Schema(
        description = "Whether the query returns a sample of the matching patients. It is written when the row is inserted and never changed afterward. An absent value is stored as false.",
        example = "false"
    ) Boolean sample,
    @Schema(
        description = "The result type of the query as a ResultType constant name, or null when the query has none. It is written when the row is inserted and never changed by a later save.",
        example = "DATAFRAME"
    ) String resultType
) {

    /**
     * Builds a request with no id, owner, consents, trigger or general query, which the operations service stores as a new row under a
     * random id.
     *
     * @param query the query request as a JSON string
     * @param resourceResultId the id HPDS assigned to the result
     * @param status the status as a {@link PicSureStatus} constant name
     * @param version the query format version
     * @param metadata result metadata as base64 text
     */
    public SaveQueryRequest(String query, String resourceResultId, String status, String version, String metadata) {
        this(query, resourceResultId, status, version, metadata, null, null, null, null);
    }

    /**
     * Builds a request that names no general query and no variant. The operations service refuses it when it names an owner, because an
     * owned row is always filed under a general query.
     *
     * @param query the query request as a JSON string
     * @param resourceResultId the id HPDS assigned to the result
     * @param status the status as a {@link PicSureStatus} constant name
     * @param version the query format version
     * @param metadata result metadata as base64 text
     * @param picsureId the id to store the row under
     * @param userUuid the UUID of the user who owns the row
     * @param consents the consents the query was scoped with
     * @param trigger why the query was submitted to HPDS, as a run trigger name
     */
    public SaveQueryRequest(
        String query, String resourceResultId, String status, String version, String metadata, UUID picsureId, UUID userUuid,
        Map<String, List<String>> consents, String trigger
    ) {
        this(query, resourceResultId, status, version, metadata, picsureId, userUuid, consents, trigger, null, null, null);
    }
}
