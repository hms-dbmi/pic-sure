package edu.harvard.hms.dbmi.avillach.auth.model;

public record ValidRefreshToken(String token, String expirationDate) implements RefreshToken {
}
