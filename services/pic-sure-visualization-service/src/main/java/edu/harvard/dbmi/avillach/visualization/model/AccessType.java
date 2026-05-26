package edu.harvard.dbmi.avillach.visualization.model;

public enum AccessType {

    AUTHORIZED("Authorized"), OPEN("Open");

    public static final String HEADER_NAME = "request-source";

    private final String value;

    AccessType(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }

    public static AccessType fromHeader(String headerValue) {
        if (AUTHORIZED.value.equalsIgnoreCase(headerValue)) {
            return AUTHORIZED;
        }
        return OPEN;
    }
}
