package edu.harvard.hms.dbmi.avillach.auth.model.request;

import jakarta.validation.constraints.NotBlank;

/**
 * Body for {@code POST /connection}. {@code id} is the connection's business identifier and must be unique;
 * {@code ConnectionWebService#addConnection} rejects a duplicate.
 */
public record ConnectionCreateRequest(
    @NotBlank String id, @NotBlank String label, @NotBlank String subPrefix, @NotBlank String requiredFields
) {
}
