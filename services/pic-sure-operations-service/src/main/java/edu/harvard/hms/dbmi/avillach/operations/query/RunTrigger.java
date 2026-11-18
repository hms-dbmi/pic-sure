package edu.harvard.hms.dbmi.avillach.operations.query;

/** Why a query was submitted to HPDS. The constant name is what the {@code query_run.trigger} column stores. */
public enum RunTrigger {

    /** The first submit of a query by its owner. */
    FIRST_SUBMIT,

    /** A later submit of a query whose row already exists. */
    RESUBMIT,

    /** A run started because the owner's consents differ from the ones the row was last run under. */
    CONSENT_CHANGE,

    /** A run started because HPDS no longer holds the result. */
    LOST_RESULT
}
