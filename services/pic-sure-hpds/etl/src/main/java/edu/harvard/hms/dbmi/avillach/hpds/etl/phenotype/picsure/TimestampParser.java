package edu.harvard.hms.dbmi.avillach.hpds.etl.phenotype.picsure;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.time.temporal.ChronoField;
import java.util.regex.Pattern;

/**
 * Timestamp parsing for the allConcepts CSV timestamp column.
 *
 * <p>Deterministic chain; zoneless formats are treated as UTC: <ol> <li>blank, or {@code 0} (the legacy "no timestamp" marker):
 * {@code null}</li> <li>an integer: epoch milliseconds, taken as is (the contract of the legacy CSV loaders, and what the splitter output
 * carries)</li> <li>ISO-8601 instant with zone or offset (e.g. {@code 2021-01-15T10:30:00Z}, {@code 2021-01-15T10:30:00+02:00})</li>
 * <li>space-separated local date-time (e.g. {@code 2023-11-25 14:00:00}), the EHR export format</li> <li>ISO-8601 local date-time (e.g.
 * {@code 2025-02-28T12:56:40.500})</li> <li>ISO-8601 local date (e.g. {@code 2025-02-28}), midnight UTC</li> </ol>
 *
 * <p>There is deliberately no unit detection for integers: a ten digit epoch-seconds value is read as milliseconds, like the legacy loaders
 * read it. Anything that matches none of the above throws, so callers can tell invalid from absent.
 */
final class TimestampParser {

    private static final Pattern EPOCH_MILLIS = Pattern.compile("-?\\d{1,18}");

    // "uuuu-MM-dd HH:mm:ss" with optional fractional seconds of any precision
    // (real EHR exports mix "2023-11-25 14:00:00", "...53.547", "...15.9")
    private static final DateTimeFormatter SPACE_SEPARATED =
        new DateTimeFormatterBuilder().appendPattern("uuuu-MM-dd HH:mm:ss").optionalStart()
            .appendFraction(ChronoField.NANO_OF_SECOND, 1, 9, true).optionalEnd().toFormatter().withResolverStyle(ResolverStyle.STRICT);

    private TimestampParser() {}

    /**
     * Parses a raw timestamp cell.
     *
     * @param raw the cell text, may be null
     * @return the instant, or null when the cell is absent: null, blank or {@code 0}
     * @throws DateTimeParseException if the text is present but matches no supported format
     */
    static Instant parse(String raw) {
        if (raw == null) {
            return null;
        }
        String text = raw.trim();
        if (text.isEmpty() || text.equals("0")) {
            return null;
        }
        if (EPOCH_MILLIS.matcher(text).matches()) {
            return Instant.ofEpochMilli(Long.parseLong(text));
        }
        try {
            // ISO-8601 instant with Z or an offset
            return Instant.parse(text);
        } catch (DateTimeParseException e1) {
            try {
                return LocalDateTime.parse(text, SPACE_SEPARATED).toInstant(ZoneOffset.UTC);
            } catch (DateTimeParseException e2) {
                try {
                    return LocalDateTime.parse(text, DateTimeFormatter.ISO_LOCAL_DATE_TIME).toInstant(ZoneOffset.UTC);
                } catch (DateTimeParseException e3) {
                    // throws if unparseable
                    return LocalDate.parse(text, DateTimeFormatter.ISO_LOCAL_DATE).atStartOfDay().toInstant(ZoneOffset.UTC);
                }
            }
        }
    }
}
