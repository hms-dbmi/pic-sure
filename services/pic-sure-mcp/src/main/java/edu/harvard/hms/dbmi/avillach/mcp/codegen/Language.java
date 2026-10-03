package edu.harvard.hms.dbmi.avillach.mcp.codegen;

/** The languages {@code get_adapter_code} writes code in. The constants are lowercase because they are the values the model sends. */
public enum Language {

    /** Python, through the {@code picsure} Python adapter. */
    python,

    /** R, through the {@code picsure} R adapter. */
    r,

    /** A bash script that calls the REST API with {@code curl}. */
    bash
}
