package edu.harvard.hms.dbmi.avillach.auth.model.response;

import edu.harvard.hms.dbmi.avillach.auth.entity.Connection;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * An identity provider connection as the connection and mapping endpoints return it, and as it is nested in a user and in a metadata
 * mapping. Every member is always written, so a member the row does not hold is {@code null} on the wire.
 *
 * @param uuid the row identifier
 * @param label the display label
 * @param id the business identifier
 * @param subPrefix the prefix of the subjects of users who sign in through this connection
 * @param requiredFields a JSON array, carried as a string, of the fields a user record on this connection must hold
 */
@Schema(description = "An identity provider connection that users sign in through. Every member is always present.")
public record ConnectionResponse(
    @Schema(
        description = "Row identifier of the connection.", example = "8694e3d4-5cb4-410f-8431-993445e6d3f6",
        requiredMode = Schema.RequiredMode.REQUIRED
    ) UUID uuid,
    @Schema(
        description = "Label shown for the connection on the login and admin screens.", example = "FENCE",
        requiredMode = Schema.RequiredMode.REQUIRED
    ) String label,
    @Schema(
        description = "Business identifier of the connection. The user and mapping endpoints name a connection by this value, and "
            + "GET and DELETE /connection/{connectionId} take it in the path.",
        example = "fence", requiredMode = Schema.RequiredMode.REQUIRED
    ) String id,
    @Schema(
        description = "Prefix of the subject of every user who signs in through this connection.", example = "fence|",
        requiredMode = Schema.RequiredMode.REQUIRED
    ) String subPrefix,
    @Schema(
        description = "The fields a user record on this connection must carry. The value is one string holding a JSON array, which "
            + "a client parses a second time.",
        example = "[{\"label\":\"Email\",\"id\":\"email\"}]", requiredMode = Schema.RequiredMode.REQUIRED
    ) String requiredFields
) {

    /**
     * Copies a persisted connection into its response shape.
     *
     * <p>A {@code null} connection yields {@code null}.</p>
     *
     * @param connection the persisted connection, or {@code null}
     * @return the response record, or {@code null} when {@code connection} is {@code null}
     */
    public static ConnectionResponse from(Connection connection) {
        if (connection == null) {
            return null;
        }
        return new ConnectionResponse(
            connection.getUuid(), connection.getLabel(), connection.getId(), connection.getSubPrefix(), connection.getRequiredFields()
        );
    }

    /**
     * Copies a collection of persisted connections in its iteration order.
     *
     * <p>A {@code null} collection yields {@code null}.</p>
     *
     * @param connections the persisted connections, or {@code null}
     * @return the response records in the same order, or {@code null} when {@code connections} is {@code null}
     */
    public static List<ConnectionResponse> fromAll(Collection<Connection> connections) {
        if (connections == null) {
            return null;
        }
        return connections.stream().map(ConnectionResponse::from).toList();
    }
}
