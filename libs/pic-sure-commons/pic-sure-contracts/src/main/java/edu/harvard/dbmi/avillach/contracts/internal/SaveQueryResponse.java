package edu.harvard.dbmi.avillach.contracts.internal;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

/**
 * 201 body of {@code POST /internal/queries}: the id the store minted for the new row, which the caller then uses for every subsequent
 * status update and dispatch.
 */
@Schema(description = "Identifier minted by the internal query store for a newly persisted query")
public record SaveQueryResponse(@Schema(description = "PIC-SURE-wide id of the persisted query") UUID picsureId) {
}
