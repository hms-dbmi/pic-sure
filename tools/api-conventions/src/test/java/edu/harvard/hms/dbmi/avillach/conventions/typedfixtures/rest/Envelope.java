package edu.harvard.hms.dbmi.avillach.conventions.typedfixtures.rest;

/**
 * A service-owned generic envelope. The rule looks through it to its type argument.
 *
 * @param message a human-readable outcome
 * @param content the payload
 * @param <T> the payload type
 */
public record Envelope<T>(String message, T content) {}
