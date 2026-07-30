package edu.harvard.hms.dbmi.avillach.auth.model.request;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import edu.harvard.dbmi.avillach.contracts.auth.TargetedRequest;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * The body of {@code POST /open/validate}: the same {@code request} node introspection carries, plus the {@code ipAddress} marker the
 * gateway sends for open access ({@code OpenAccessFilter#openAccessIpAddress} -- {@code "OPEN_ACCESS:<host>"}). Reusing
 * {@link TargetedRequest} is what keeps deployed JsonPath access rules resolving identically on the open path and the authorized path;
 * these rules are database rows, so the node they bind to cannot be re-shaped here.
 *
 * <p><b>Tolerant reader, deliberately.</b> This is the OPEN access path: a request that PSAMA refuses to bind is a 400 back to the gateway,
 * which fails the request closed -- a user-visible outage for unauthenticated users. The endpoint previously bound
 * {@code Map<String, Object>} and narrowed it leniently inside {@code AuthorizationService}, so an unmodelled key has always been dropped
 * rather than rejected; {@code ignoreUnknown} preserves exactly that under PSAMA's strict-by-default ObjectMapper. The trade is real and
 * accepted: a client typo in a key is silently ignored here instead of surfacing as a 400, which on this endpoint is the safer failure.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@Schema(description = "Open-access validation request sent by the gateway to PSAMA for unauthenticated traffic")
public record OpenAccessValidationRequest(
    @Schema(description = "The request being authorized; identical node to token introspection") TargetedRequest request,
    @Schema(description = "Open-access caller marker, \"OPEN_ACCESS:<host>\"") String ipAddress
) {
}
