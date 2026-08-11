package edu.harvard.hms.dbmi.avillach.auth.model.response;


/**
 * The body of {@code POST /open/validate}, replacing a bare JSON {@code true}/{@code false} document.
 *
 * <p><b>This is a lockstep change with the gateway.</b> {@code PsamaClient#validateOpenAccess} used to require
 * {@code JsonNode#isBoolean()}, so an object body would have read as "not valid" and denied every open-access request. That client now
 * accepts both shapes, which makes a new gateway compatible with an old PSAMA -- so the gateway must be deployed BEFORE PSAMA. An old
 * gateway against a new PSAMA denies all open access.
 */
public record ValidationResponse(boolean valid) {
}
