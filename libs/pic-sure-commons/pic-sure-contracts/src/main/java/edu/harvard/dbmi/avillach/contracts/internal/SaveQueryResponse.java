package edu.harvard.dbmi.avillach.contracts.internal;


import java.util.UUID;

/**
 * 201 body of {@code POST /internal/queries}: the id the store minted for the new row, which the caller then uses for every subsequent
 * status update and dispatch.
 */
public record SaveQueryResponse(UUID picsureId) {
}
