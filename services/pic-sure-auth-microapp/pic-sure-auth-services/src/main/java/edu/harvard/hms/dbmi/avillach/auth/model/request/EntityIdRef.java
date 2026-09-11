package edu.harvard.hms.dbmi.avillach.auth.model.request;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * A reference to an existing record by its UUID. Association members of the request records use this so a request can only point at a
 * persisted row; the service resolves the row and attaches the managed entity, never the caller's copy of it.
 */
public record EntityIdRef(@NotNull UUID uuid) {
}
