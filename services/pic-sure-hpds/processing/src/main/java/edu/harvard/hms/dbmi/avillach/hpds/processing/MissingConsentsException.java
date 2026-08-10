package edu.harvard.hms.dbmi.avillach.hpds.processing;

public class MissingConsentsException extends RuntimeException {
    public MissingConsentsException(String message) {
        super(message);
    }
}
