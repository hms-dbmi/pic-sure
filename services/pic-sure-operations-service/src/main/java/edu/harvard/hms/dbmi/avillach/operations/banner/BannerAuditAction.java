package edu.harvard.hms.dbmi.avillach.operations.banner;

public enum BannerAuditAction {

    SAVED("banner.saved"),
    UPDATED("banner.updated"),
    PUBLISHED("banner.published"),
    SCHEDULED("banner.scheduled"),
    REORDERED("banner.reordered"),
    DISABLED("banner.disabled"),
    ARCHIVED("banner.archived"),
    RESTORED("banner.restored");

    private final String value;

    BannerAuditAction(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }
}
