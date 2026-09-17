package edu.harvard.hms.dbmi.avillach.auth.exceptions;

public class IdpProvisioningException extends RuntimeException {
    public IdpProvisioningException(String message) {
        super(message);
    }

    public IdpProvisioningException(String message, Throwable cause) {
        super(message, cause);
    }
}
