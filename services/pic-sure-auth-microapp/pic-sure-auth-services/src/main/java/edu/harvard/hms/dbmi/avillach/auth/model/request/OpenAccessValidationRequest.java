package edu.harvard.hms.dbmi.avillach.auth.model.request;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.HashMap;
import java.util.Map;

/**
 * The body of {@code POST /open/validate}, which the gateway sends for every request that carries no bearer token. The request description
 * is kept as a JSON tree because the open access rules evaluate JSON paths against it, and a key dropped by a typed binding could change a
 * rule's verdict.
 *
 * @param apiKey the open access API key the caller presented, when key enforcement is on
 * @param ipAddress the marker the gateway builds from the request host; recorded by the gateway, not read here
 * @param request a description of the request, evaluated by the open access rules
 * @param responseVersion {@code 2} asks for an {@code OpenAccessValidationResponse} instead of a bare boolean; kept as a JSON tree so a
 *        string {@code "2"} is not coerced to the number and still gets the boolean
 */
@Schema(description = "An open access request to validate against the open access rules. Any other member is ignored.")
@JsonIgnoreProperties(ignoreUnknown = true)
public record OpenAccessValidationRequest(
    @Schema(
        description = "The open access API key the caller presented. Required only when API key enforcement is on.",
        example = "picsure_u_00000000000000000000000000000000000000000003tr27S"
    ) String apiKey,
    @Schema(
        description = "Marker the gateway builds from the request host. Accepted and not read.", example = "OPEN_ACCESS:localhost"
    ) String ipAddress,
    @Schema(
        implementation = Map.class,
        description = "Description of the request, evaluated by the open access rules as sent. The gateway sends \"Target Service\", "
            + "the path within the application; an application may add \"query\" with the query body."
    ) JsonNode request,
    @Schema(
        type = "integer",
        description = "2 to receive an OpenAccessValidationResponse instead of a bare boolean. Absent or any other value gives the boolean.",
        example = "2"
    ) JsonNode responseVersion
) {

    /**
     * Returns the body as the map the authorization service reads, with {@code request} converted to the plain maps, lists and scalars of a
     * {@code Map<String, Object>}. A body with no members gives an empty map, which the service treats as a request with nothing to
     * evaluate.
     *
     * @param mapper the mapper that converts the request tree
     * @return a mutable map holding the members that were sent
     */
    public Map<String, Object> toMap(ObjectMapper mapper) {
        Map<String, Object> map = new HashMap<>();
        if (apiKey != null) {
            map.put("apiKey", apiKey);
        }
        if (ipAddress != null) {
            map.put("ipAddress", ipAddress);
        }
        if (request != null && !request.isNull()) {
            map.put("request", mapper.convertValue(request, Object.class));
        }
        if (responseVersion != null && !responseVersion.isNull()) {
            map.put("responseVersion", mapper.convertValue(responseVersion, Object.class));
        }
        return map;
    }

    /** The API key is a secret (a key or session token), so it never appears in logs, including Spring's request-body debug line. */
    @Override
    public String toString() {
        return "OpenAccessValidationRequest[apiKey=" + (apiKey == null ? null : "[REDACTED]") + ", ipAddress=" + ipAddress + ", request="
            + request + ", responseVersion=" + responseVersion + "]";
    }
}
