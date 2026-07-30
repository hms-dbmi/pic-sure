package edu.harvard.dbmi.avillach.contracts.audit;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 202 body of the logging service's audit intake: the event was accepted for processing, not that it has been written anywhere yet.
 */
@Schema(description = "Acknowledgement that an audit event was accepted for processing")
public record AuditAccepted(@Schema(description = "Always \"accepted\"; the event is queued, not yet persisted") String status) {
}
