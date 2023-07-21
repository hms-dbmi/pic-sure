package edu.harvard.hms.dbmi.avillach.resource.visualization.model.domain;

public enum AccessType {

    HEADER_NAME("request-source"),
    OPEN_ACCESS("Open"),
    AUTHORIZED_ACCESS("Authorized");

    private String value;

    AccessType(String value) {
        this.value = value;
    }

    public String getValue() {
        return this.value;
    }
}

