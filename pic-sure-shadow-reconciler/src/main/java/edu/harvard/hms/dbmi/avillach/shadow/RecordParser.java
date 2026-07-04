package edu.harvard.hms.dbmi.avillach.shadow;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Parses shadow-log lines into {@link ShadowRecord}s. Each line is expected to contain exactly one minified JSON object matching the shared
 * shadow-record schema, but is tolerant of surrounding log-framework text (timestamp, level, logger name, thread) that a real logging
 * backend (Logback/SLF4J) prepends to the message before the JSON payload begins.
 *
 * <p>It is additionally tolerant of the aio structured-JSON <em>envelope</em>: if the gateway's {@code picsure.shadow} output is routed
 * through Logback's {@code JsonEncoder} (rather than the dedicated raw {@code %msg%n} appender), the real record arrives escaped inside the
 * envelope's {@code "message"} field alongside {@code timestamp}/{@code level}/{@code loggerName}/... This parser detects that shape and
 * unwraps the inner record. This is belt-and-braces: the gateway ships a dedicated raw appender so {@code grep '"side":"GW"'} yields bare
 * lines, but logs collected before that config shipped still parse.
 */
public final class RecordParser {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private RecordParser() {}

    /**
     * Parses a single shadow-log line. Extracts the JSON object (from the first '{' to the last '}' on the line) so that lines carrying a
     * log-framework prefix such as {@code "2026-07-03 10:00:00.000 INFO 1 --- [main] picsure.shadow : {...}"} still parse correctly, and
     * unwraps the aio structured-JSON envelope (record escaped inside {@code "message"}) when present.
     *
     * @throws IllegalArgumentException if no JSON object can be found or extracted, or it does not deserialize to a valid
     *         {@link ShadowRecord}.
     */
    public static ShadowRecord parseLine(String line) {
        String json = extractJson(line);
        try {
            JsonNode node = MAPPER.readTree(json);
            if (isEnvelope(node)) {
                // aio JsonEncoder envelope: the real record is escaped inside "message". Unwrap and parse the inner record.
                node = MAPPER.readTree(node.get("message").asText());
            }
            return MAPPER.treeToValue(node, ShadowRecord.class);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Bad shadow line: " + line, e);
        }
    }

    /**
     * True iff {@code node} is a Logback structured-JSON envelope rather than a bare shadow record: it carries a textual {@code "message"}
     * field (the escaped record) and lacks a shadow record's required discriminators ({@code "side"}/{@code "correlationId"}). A real bare
     * record always carries those and never a top-level {@code "message"}, so this cannot mistake one for the other.
     */
    private static boolean isEnvelope(JsonNode node) {
        return node != null && node.isObject() && node.hasNonNull("message") && node.get("message").isTextual() && !node.has("side")
            && !node.has("correlationId");
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
