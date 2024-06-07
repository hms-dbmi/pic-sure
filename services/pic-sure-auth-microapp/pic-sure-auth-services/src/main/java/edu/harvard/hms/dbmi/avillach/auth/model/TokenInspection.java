package edu.harvard.hms.dbmi.avillach.auth.model;

import io.jsonwebtoken.Claims;

import java.util.HashMap;
import java.util.Map;

/**
 * inner used token introspection class with active:false included
 */
public class TokenInspection {

    Map<String, Object> responseMap = new HashMap<>();
    String message = null;

    public TokenInspection() {
        responseMap.put("active", false);
    }

    public TokenInspection(String message) {
        responseMap.put("active", false);
        this.message = message;
    }

    public Map<String, Object> getResponseMap() {
        return responseMap;
    }

    public void setResponseMap(Map<String, Object> responseMap) {
        this.responseMap = responseMap;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public void addField(String key, Object value) {
        responseMap.put(key, value);
    }

    public void addAllFields(Claims body) {
        responseMap.putAll(body);
    }
}
