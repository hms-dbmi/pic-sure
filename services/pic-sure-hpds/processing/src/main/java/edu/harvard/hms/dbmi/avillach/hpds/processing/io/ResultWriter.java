package edu.harvard.hms.dbmi.avillach.hpds.processing.io;

import org.springframework.http.MediaType;

import java.io.File;
import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.Set;

public interface ResultWriter {
    void writeHeader(String[] data);

    void writeEntity(Collection<String[]> data);

    void writeMultiValueEntity(Collection<List<List<String>>> data);

    /**
     * Names the concept paths whose observations carry timestamps, so a writer that supports them can add a timestamp column beside each.
     * Must be called before the first rows are written. Writers without timestamp support ignore the call.
     *
     * @param conceptPaths the concept paths, as passed to {@link #writeHeader(String[])}, that have at least one dated observation
     * @throws IllegalStateException if the writer has already fixed its columns
     */
    default void declareTimestampedFields(Set<String> conceptPaths) {}

    /**
     * Writes rows that carry a timestamp for each value. Writers without timestamp support write the values and drop the timestamps.
     *
     * @param data one entry per row, each holding one list of values per header field
     * @param timestamps the same shape as {@code data}: one entry per row, in the same order, each holding one entry per header field. For
     *        a field declared through {@link #declareTimestampedFields(Set)} the entry is a list as long as that field's values, where
     *        element {@code i} is the ISO-8601 time of value {@code i}, or null when that value is undated. For any other field the entry
     *        is null.
     * @throws IllegalArgumentException if a writer that supports timestamps finds the two arguments out of step
     */
    default void writeMultiValueEntity(Collection<List<List<String>>> data, Collection<List<List<String>>> timestamps) {
        writeMultiValueEntity(data);
    }

    File getFile();

    MediaType getResponseType();

    void close();
}
