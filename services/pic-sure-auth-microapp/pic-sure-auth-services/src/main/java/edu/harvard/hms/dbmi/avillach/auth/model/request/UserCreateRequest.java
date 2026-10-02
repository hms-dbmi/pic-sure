package edu.harvard.hms.dbmi.avillach.auth.model.request;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;

import java.util.Set;

/**
 * One user in the body of {@code POST /user}. The row identifier is generated on persist, and everything an identity provider or a login
 * flow owns is absent: {@code subject}, {@code passport}, the long-term {@code token}, {@code acceptedTOS}, {@code matched} and
 * {@code auth0metadata}.
 *
 * @param email the user's email; when absent it is read from an email key in {@code generalMetadata}
 * @param active whether the user is active; absent means active
 * @param generalMetadata a JSON object of profile metadata
 * @param connection the existing connection the user signs in through
 * @param roles existing roles to grant, by UUID; at least one is required
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record UserCreateRequest(
    String email, Boolean active, String generalMetadata, @Valid ConnectionRef connection, @NotEmpty @Valid Set<EntityIdRef> roles
) {
}
