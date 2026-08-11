package edu.harvard.dbmi.avillach.contracts.internal;

import edu.harvard.dbmi.avillach.contracts.query.v3.PicSureStatus;

/**
 * Body of {@code PATCH /internal/queries/{picsureId}}. Every field is nullable and an absent field means "leave unchanged", so a caller can
 * advance just the status as a dispatch completes without re-sending the whole row.
 */
public record UpdateQueryRequest(PicSureStatus status, String resourceResultId, String metadata) {
}
