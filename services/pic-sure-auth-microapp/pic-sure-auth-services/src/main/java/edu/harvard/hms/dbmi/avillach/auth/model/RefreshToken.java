package edu.harvard.hms.dbmi.avillach.auth.model;

public sealed interface RefreshToken permits ValidRefreshToken, InvalidRefreshToken {
}

