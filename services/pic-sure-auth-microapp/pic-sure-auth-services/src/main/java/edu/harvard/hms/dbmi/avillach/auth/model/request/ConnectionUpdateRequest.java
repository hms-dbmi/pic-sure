package edu.harvard.hms.dbmi.avillach.auth.model.request;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/** Body for {@code PUT /connection}. Absent members leave the stored value unchanged. */
public record ConnectionUpdateRequest(@NotNull UUID uuid, String id, String label, String subPrefix, String requiredFields) {
}
