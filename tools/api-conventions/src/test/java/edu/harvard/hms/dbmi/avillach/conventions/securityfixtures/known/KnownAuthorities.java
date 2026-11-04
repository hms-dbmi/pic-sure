package edu.harvard.hms.dbmi.avillach.conventions.securityfixtures.known;

import java.util.List;

/** Stands in for PSAMA's authority constants: only public static final String fields count as known names. */
public class KnownAuthorities {

    public static final String ADMIN = "ADMIN";
    public static final String SUPER_ADMIN = "SUPER_ADMIN";
    public static final String PRIV_DATA_ADMIN = "PRIV_DATA_ADMIN";

    public static final int NOT_A_NAME = 1;
    static final String PACKAGE_PRIVATE = "PACKAGE_PRIVATE";
    public final String instanceField = "INSTANCE";

    public static List<String> all() {
        return List.of(ADMIN, SUPER_ADMIN, PRIV_DATA_ADMIN);
    }
}
