package edu.harvard.dbmi.avillach.visualization.model;

public enum AccessType {

    AUTHORIZED("authorized"), OPEN("open");

    private final String value;

    AccessType(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }

}
