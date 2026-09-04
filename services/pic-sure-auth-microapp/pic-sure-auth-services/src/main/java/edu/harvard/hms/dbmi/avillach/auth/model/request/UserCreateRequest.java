package edu.harvard.hms.dbmi.avillach.auth.model.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;

import java.util.Set;

/**
 * Body for {@code POST /user}. Everything an identity provider owns is absent by construction: {@code subject}, {@code passport}, the
 * long-term {@code token}, {@code acceptedTOS}, {@code matched}, and {@code auth0metadata} are set by the login and terms-of-service flows,
 * never by an administrator's request body.
 */
public record UserCreateRequest(
    String email, Boolean active, String generalMetadata, @Valid ConnectionRef connection, @NotEmpty @Valid Set<EntityIdRef> roles
) {
}
