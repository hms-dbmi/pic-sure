package edu.harvard.dbmi.avillach.contracts.audit;


/**
 * 202 body of the logging service's audit intake: the event was accepted for processing, not that it has been written anywhere yet.
 */
public record AuditAccepted(String status) {
}
