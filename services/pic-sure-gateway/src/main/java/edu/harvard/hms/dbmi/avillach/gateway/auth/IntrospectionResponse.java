package edu.harvard.hms.dbmi.avillach.gateway.auth;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record IntrospectionResponse(
    boolean active, String userId, String sub, String email, String roles, List<String> privileges, Boolean tokenRefreshed, String token,
    String query
) {
}
