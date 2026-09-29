package edu.harvard.hms.dbmi.avillach.mcp.codegen;

/**
 * The per-deployment facts generated code depends on: where it connects, which connection flags the site needs, and the oldest adapter
 * releases it supports.
 *
 * @param baseUrl the PIC-SURE URL generated code connects to
 * @param includeConsents whether generated code passes {@code include_consents=True}
 * @param supportsGenomic whether generated code passes {@code supports_genomic=True}
 * @param pythonMinVersion the oldest {@code picsure} Python adapter release the code supports
 * @param rTag the R adapter release tag R code installs, blank when not configured
 */
public record AdapterSetup(String baseUrl, boolean includeConsents, boolean supportsGenomic, String pythonMinVersion, String rTag) {
}
