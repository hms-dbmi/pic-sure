package edu.harvard.dbmi.avillach.logging.service;

public class AuditLogException extends RuntimeException {

    public AuditLogException(String message, Throwable cause) {
        super(message, cause);
    }
}
