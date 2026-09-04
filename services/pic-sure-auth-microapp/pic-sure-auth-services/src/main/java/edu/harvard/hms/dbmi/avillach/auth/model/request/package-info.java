/**
 * Allowlisted request records for the administrative endpoints. Controllers bind these instead of the JPA entities in
 * {@code edu.harvard.hms.dbmi.avillach.auth.entity}, so a caller can only set the fields a create or update is defined to accept. Every
 * server-owned value -- the inherited {@code uuid}, {@code Application.token}, and {@code User.subject}/{@code passport}/{@code token}/
 * {@code acceptedTOS}/{@code matched} -- is absent from these records by construction and therefore unreachable from a request body.
 *
 * <p>Create records carry no {@code uuid} at all: the identifier is generated on persist, so a create can never target an existing row.
 * Update records require one, and the service loads that row and copies only the fields present in the request.
 */
package edu.harvard.hms.dbmi.avillach.auth.model.request;
