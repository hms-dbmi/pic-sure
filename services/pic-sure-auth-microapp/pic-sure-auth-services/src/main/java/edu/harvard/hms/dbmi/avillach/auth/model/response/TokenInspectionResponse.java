package edu.harvard.hms.dbmi.avillach.auth.model.response;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * The body of {@code POST /token/inspect}. The named members are the keys the gateway reads. The gateway's {@code IntrospectionResponse} is
 * the reader of these members. Every other claim of the introspected token ({@code iss}, {@code iat}, {@code exp}, {@code jti},
 * {@code sid}, {@code name}, {@code idp} and the RAS claims) is written beside them through {@link #claims()}.
 *
 * <p>A rejected token carries only {@code active} and {@code message}. An accepted token carries {@code active}, the claims, {@code roles},
 * {@code privileges}, and either {@code tokenRefreshed} with a replacement {@code token} or {@code tokenRefreshed} false. A member that is
 * {@code null} is left off the wire.</p>
 *
 * @param active whether the token is valid and the request is authorized
 * @param message why the token was rejected, or {@code null}
 * @param token a replacement token issued because the presented one is about to expire, or {@code null}
 * @param tokenRefreshed whether a replacement token was issued, or {@code null} when the verdict did not get that far
 * @param uuid the user's row identifier, from the token
 * @param sub the token's subject
 * @param email the user's email, from the token
 * @param roles the user's role names joined with commas
 * @param privileges the user's privilege names for the calling application and the application-free ones
 * @param claims every other claim of the token, written beside the named members
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(
    description = "A token introspection verdict. The named members are what the gateway reads; every other claim of the token "
        + "(iss, iat, exp, jti, sid, name, idp and the RAS claims) is written beside them. A rejected token carries only active and "
        + "message. A null member is absent."
)
public record TokenInspectionResponse(
    @Schema(
        description = "Whether the token is valid and the request is authorized.", requiredMode = Schema.RequiredMode.REQUIRED
    ) boolean active,
    @Schema(description = "Why the token was rejected. Absent when it was accepted.", example = "Token not found") String message,
    @Schema(
        description = "A replacement token, issued because the presented one is about to expire. Absent otherwise.",
        example = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJmZW5jZXwxMjM0NSJ9.sflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV_adQssw5c"
    ) String token,
    @Schema(description = "Whether a replacement token was issued. Absent when the token was rejected.") Boolean tokenRefreshed,
    @Schema(description = "Row identifier of the user, from the token.", example = "8694e3d4-5cb4-410f-8431-993445e6d3f6") String uuid,
    @Schema(description = "Subject of the token.", example = "fence|12345") String sub,
    @Schema(description = "Email address of the user, from the token.", example = "researcher@example.org") String email,
    @Schema(description = "Role names of the user, joined with commas.", example = "PIC-SURE Top Admin,MANAGED_phs000007_c1") String roles,
    @Schema(
        description = "Privilege names the user holds for the calling application, plus the ones bound to no application.",
        example = "[\"SUPER_ADMIN\", \"PRIV_FENCE_phs000007_c1\"]"
    ) Set<String> privileges, @Schema(hidden = true) Map<String, Object> claims
) {

    private static final Set<String> NAMED =
        Set.of("active", "message", "token", "tokenRefreshed", "uuid", "sub", "email", "roles", "privileges");

    /**
     * Returns the claims that are not named members, so Jackson writes them beside the named ones.
     *
     * @return the other claims
     */
    @JsonAnyGetter
    @Override
    public Map<String, Object> claims() {
        return claims;
    }

    /**
     * Copies the token service's result map into the response shape: the named keys into their members, every other key into
     * {@link #claims()}.
     *
     * @param result the introspection result
     * @return the response record
     */
    @SuppressWarnings("unchecked")
    public static TokenInspectionResponse from(Map<String, Object> result) {
        Map<String, Object> others = new LinkedHashMap<>();
        result.forEach((key, value) -> {
            if (!NAMED.contains(key)) {
                others.put(key, value);
            }
        });
        return new TokenInspectionResponse(
            Boolean.TRUE.equals(result.get("active")), (String) result.get("message"), (String) result.get("token"),
            (Boolean) result.get("tokenRefreshed"), (String) result.get("uuid"), (String) result.get("sub"), (String) result.get("email"),
            (String) result.get("roles"), (Set<String>) result.get("privileges"), others
        );
    }
}
