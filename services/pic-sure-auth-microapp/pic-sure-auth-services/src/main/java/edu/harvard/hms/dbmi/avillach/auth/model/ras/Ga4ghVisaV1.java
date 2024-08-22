package edu.harvard.hms.dbmi.avillach.auth.model.ras;

public class Ga4ghVisaV1 {

    private String type;
    private long asserted;
    private String value;
    private String source;
    private String by;

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public long getAsserted() {
        return asserted;
    }

    public void setAsserted(long asserted) {
        this.asserted = asserted;
    }

    public String getValue() {
        return value;
    }

    public void setValue(String value) {
        this.value = value;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public String getBy() {
        return by;
    }

    public void setBy(String by) {
        this.by = by;
    }
}
