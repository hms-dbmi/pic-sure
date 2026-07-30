package edu.harvard.dbmi.avillach.contracts.query.v3;

/**
 * PIC-SURE-wide status of a query, normalized across every backing resource. <p> The declaration order is load-bearing: callers persist the
 * ordinal, so reordering these constants silently rewrites stored statuses.
 */
public enum PicSureStatus {
    QUEUED, PENDING, ERROR, AVAILABLE
}
