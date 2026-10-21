package edu.harvard.hms.dbmi.avillach.conventions.schemafixtures.model;

/**
 * A subtype reached only through the annotation on its interface, with no documentation at all.
 *
 * @param outline the outline
 */
public record Blob(String outline) implements Shape {}
