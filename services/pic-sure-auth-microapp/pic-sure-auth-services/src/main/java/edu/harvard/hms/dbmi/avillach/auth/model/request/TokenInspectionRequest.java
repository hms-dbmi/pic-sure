package edu.harvard.hms.dbmi.avillach.auth.model.request;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.HashMap;
import java.util.Map;

/**
 * The body of {@code POST /token/inspect}, which the gateway sends for every bearer request it proxies. The request description is kept as
 * a JSON tree rather than a typed shape because access rules evaluate JSON paths against it, and a key dropped by a typed binding could
 * change a rule's verdict.
 *
 * @param token the user's bearer token to introspect
 * @param request a description of the request the user is making, evaluated by the access rules
 */
@Schema(description = "A token to introspect and a description of the request it is being used for. Any other member is ignored.")
@JsonIgnoreProperties(ignoreUnknown = true)
public record TokenInspectionRequest(
    @Schema(
        description = "The user's bearer token to introspect.",
        example = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJmZW5jZXwxMjM0NSJ9.sflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV_adQssw5c",
        requiredMode = Schema.RequiredMode.REQUIRED
    ) String token,
    @Schema(
        implementation = Map.class,
        description = "Description of the request the token is used for, evaluated by the access rules as sent. The gateway sends "
            + "\"Target Service\", the path within the application; an application may add \"query\" with the query body."
    ) JsonNode request
) {

    /**
     * Returns the body as the mutable map the token service reads, with {@code request} converted to the plain maps, lists and scalars of a
     * {@code Map<String, Object>}, so the access rules evaluate their JSON paths against plain Java values.
     *
     * @param mapper the mapper that converts the request tree
     * @return a mutable map holding the members that were sent
     */
    public Map<String, Object> toMap(ObjectMapper mapper) {
        Map<String, Object> map = new HashMap<>();
        if (token != null) {
            map.put("token", token);
        }
        if (request != null && !request.isNull()) {
            map.put("request", mapper.convertValue(request, Object.class));
        }
        return map;
    }
}
