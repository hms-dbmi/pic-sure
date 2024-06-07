package edu.harvard.hms.dbmi.avillach.auth.enums;


public enum SecurityRoles {

    ADMIN("ADMIN"),
    SUPER_ADMIN("SUPER_ADMIN"),
    PIC_SURE_TOP_ADMIN("PIC-SURE Top Admin");

    private final String role;

    SecurityRoles(String role) {
        this.role = role;
    }

    public String getRole() {
        return role;
    }

}
