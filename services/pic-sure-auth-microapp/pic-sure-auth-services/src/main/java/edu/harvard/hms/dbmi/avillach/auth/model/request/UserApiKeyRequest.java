package edu.harvard.hms.dbmi.avillach.auth.model.request;

public record UserApiKeyRequest(String captchaToken, String name, String email) {
}
