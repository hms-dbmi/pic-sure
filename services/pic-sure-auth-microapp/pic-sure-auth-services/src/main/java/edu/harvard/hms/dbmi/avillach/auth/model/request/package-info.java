/**
 * Request records for the PSAMA endpoints. The administrative create and update endpoints bind these instead of the JPA entities in
 * {@code edu.harvard.hms.dbmi.avillach.auth.entity}, so a caller can only set the fields a create or update is defined to accept. The
 * inherited {@code uuid} of a create, {@code Application.token}, and the {@code subject}, {@code passport}, {@code token},
 * {@code acceptedTOS}, {@code matched} and {@code auth0metadata} of a user are absent from these records and cannot be reached from a
 * request body.
 *
 * <p>Create records carry no {@code uuid}: the identifier is generated on persist, so a create can never target an existing row. Update
 * records require one; the service loads that row and copies only the members the request sets. Every record ignores properties it does not
 * declare, so a client that sends a whole entity, as the admin UI does, is accepted and the extra properties have no effect.</p>
 */
package edu.harvard.hms.dbmi.avillach.auth.model.request;
