package edu.harvard.hms.dbmi.avillach.gateway.auth;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record IntrospectionRequest(String token, Map<String, Object> request) {
}
