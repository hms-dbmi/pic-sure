package edu.harvard.hms.dbmi.avillach.mcp.tool;

import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.Query;
import edu.harvard.hms.dbmi.avillach.mcp.caller.CallerHeaders;
import edu.harvard.hms.dbmi.avillach.mcp.gateway.AuthorizedQueryClient;

/** Runs an authorized count call for a tool: no request without the caller's token, failures mapped without downstream text. */
final class AuthorizedQueryCalls {

    private AuthorizedQueryCalls() {}

    /**
     * Sends the query as the caller.
     *
     * @param client the authorized query client
     * @param query the query, with a count result type
     * @param caller the caller's headers
     * @return the response body, never blank
     * @throws ToolFailure when the caller has no token, the call fails, or the body is empty
     */
    static String run(AuthorizedQueryClient client, Query query, CallerHeaders caller) {
        requireToken(caller);
        String body = QueryCalls.run(
            "authorized count", () -> client.countSync(query, caller), ToolFailure::fromAuthorizedQueryDownstream,
            ToolFailure::authorizedQueryUnavailable
        );
        if (body == null || body.isBlank()) {
            throw new ToolFailure("The query service returned no result.");
        }
        return body;
    }

    /**
     * Fails when the caller has no token, so a tool can check this before its other argument checks.
     *
     * @param caller the caller's headers
     * @throws ToolFailure when the caller has no token
     */
    static void requireToken(CallerHeaders caller) {
        if (caller.authorization() == null || caller.authorization().isBlank()) {
            throw new ToolFailure("The caller has no token, so there is no authorized count. Sign in to PIC-SURE.");
        }
    }
}
