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
 * <p>A request that carries {@code picsureId} is an upsert: the first one inserts the row under that id with its owner, and a later one
 * updates the consents, status and metadata of that row and never its body, version or owner. A request that carries {@code trigger} also
 * records one run of the query.
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
    ) String trigger
) {

    /**
     * Builds a request with no id, owner, consents or trigger, which the operations service stores as a new row under a random id.
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
}
