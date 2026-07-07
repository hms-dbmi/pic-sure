package edu.harvard.dbmi.avillach.dump.entities;

public record ConceptNodeMetaDump(String conceptPath, String key, String value) implements DumpRow {
}
