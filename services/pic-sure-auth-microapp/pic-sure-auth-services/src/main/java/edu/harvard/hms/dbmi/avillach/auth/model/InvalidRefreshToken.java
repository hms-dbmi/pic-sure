package edu.harvard.hms.dbmi.avillach.auth.model;

public record InvalidRefreshToken(String error) implements RefreshToken {
}
