package edu.harvard.hms.dbmi.avillach.auth.enums;

/**
 * The kinds of open-access credential. USER and PLATFORM keys are stored in {@code api_key}. SESSION is a stateless open-access session
 * token issued by {@code OpenSessionService}; it appears only in the {@code /open/validate} answer and is never generated, stored, or
 * listed as an API key.
 */
public enum ApiKeyType {
    USER, PLATFORM, SESSION
}
