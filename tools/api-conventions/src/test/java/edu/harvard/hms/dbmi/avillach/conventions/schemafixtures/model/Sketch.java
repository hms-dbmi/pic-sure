package edu.harvard.hms.dbmi.avillach.conventions.schemafixtures.model;

/**
 * An implementation the interface does not name as a subtype. Jackson never writes it, so the walk does not
 * reach it.
 *
 * @param strokes the stroke count
 */
public record Sketch(int strokes) implements Shape {}
