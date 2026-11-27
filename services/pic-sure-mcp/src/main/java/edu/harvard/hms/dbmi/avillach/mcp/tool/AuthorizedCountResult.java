package edu.harvard.hms.dbmi.avillach.mcp.tool;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * The caller's exact participant count under their own consents.
 *
 * @param count the exact count
 */
public record AuthorizedCountResult(@Schema(description = "The exact count of participants the caller is permitted to see") int count) {
}
