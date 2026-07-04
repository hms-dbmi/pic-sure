package edu.harvard.hms.dbmi.avillach.shadow;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Parses shadow-log lines into {@link ShadowRecord}s. Each line is expected to contain exactly one minified JSON object matching the shared
 * shadow-record schema, but is tolerant of surrounding log-framework text (timestamp, level, logger name, thread) that a real logging
 * backend (Logback/SLF4J) prepends to the message before the JSON payload begins.
 */
public final class RecordParser {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private RecordParser() {}

    /**
     * Parses a single shadow-log line. Extracts the JSON object (from the first '{' to the last '}' on the line) so that lines carrying a
     * log-framework prefix such as {@code "2026-07-03 10:00:00.000 INFO 1 --- [main] picsure.shadow : {...}"} still parse correctly.
     *
     * @throws IllegalArgumentException if no JSON object can be found or extracted, or it does not deserialize to a valid
     *         {@link ShadowRecord}.
     */
    public static ShadowRecord parseLine(String line) {
        String json = extractJson(line);
        try {
            return MAPPER.readValue(json, ShadowRecord.class);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Bad shadow line: " + line, e);
        }
    }

    /** Parses each non-blank line via {@link #parseLine(String)}, skipping blank lines. */
    public static List<ShadowRecord> parseLines(Stream<String> lines) {
        return lines.map(String::trim).filter(s -> !s.isEmpty()).map(RecordParser::parseLine).collect(Collectors.toList());
    }

    private static String extractJson(String line) {
        int start = line.indexOf('{');
        int end = line.lastIndexOf('}');
        if (start < 0 || end < start) {
            throw new IllegalArgumentException("Bad shadow line: " + line);
        }
        return line.substring(start, end + 1);
    }
}
