package edu.harvard.hms.dbmi.avillach.mcp.codegen;

/**
 * The per-deployment facts generated code depends on: where it connects, which connection flags the site needs, and the oldest adapter
 * releases it supports.
 *
 * @param baseUrl the PIC-SURE URL generated code connects to, stored without trailing slashes so every language writes the same value
 * @param includeConsents whether generated code passes {@code include_consents=True}
 * @param supportsGenomic whether generated code passes {@code supports_genomic=True}
 * @param pythonMinVersion the oldest {@code picsure} Python adapter release the code supports
 * @param rTag the R adapter release tag R code installs, blank when not configured
 */
public record AdapterSetup(String baseUrl, boolean includeConsents, boolean supportsGenomic, String pythonMinVersion, String rTag) {

    /** Strips trailing slashes from the base URL, once, so the Python, R, and bash generators all see the same value. */
    public AdapterSetup {
        if (baseUrl != null) {
            while (baseUrl.endsWith("/")) {
                baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
            }
        }
    }
}
