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
import java.util.List;
import java.util.regex.Pattern;

/**
 * The validity gate of a row of the consents variable ({@code \_consents\}): only a row that passes it may grant a consent, because the
 * consent split is an access-control decision and a doubtful row must grant nothing.
 *
 * <p>The rules are those of {@code SplitRowCheck} of the split-allconcepts-by-consent job (pic-sure-hpds-etl@6416d91), applied to the raw
 * fields as they are, without trimming: <ul> <li>exactly 5 fields ({@link Problem#BAD_FIELD_COUNT});</li> <li>the patient id is an integer
 * of at most 11 characters, parsed <b>untrimmed</b> ({@code " 5"} is not one) ({@link Problem#BAD_PATIENT_NUM});</li> <li>nval is empty or
 * a number {@code Double.parseDouble} accepts ({@link Problem#BAD_NVAL} otherwise);</li> <li>the timestamp is empty, an integer that fits a
 * long, or an ISO form the splitter converts with {@code --timestamps=convert-iso}: an instant with {@code Z} or an offset, a
 * {@code uuuu-MM-dd HH:mm:ss} date-time with an optional fraction, an ISO local date-time, an ISO local date ({@link Problem#BAD_TIMESTAMP}
 * otherwise).</li> </ul>
 *
 * <p><b>The one deliberate difference from the splitter:</b> a consent row with a non-blank nval does not grant either, also when the
 * number is valid ({@link Problem#NUMERIC_NVAL}). The splitter takes the consent from the text value and ignores the number; the loader
 * would store the number of that row while routing by the text, two readings of one row. Failing closed costs nothing: a consent row
 * carries its consent in the text value, with an empty number.
 */
final class ConsentRowCheck {

    /** Why a consent row grants nothing. */
    enum Problem {
        BAD_FIELD_COUNT, BAD_PATIENT_NUM, BAD_NVAL, NUMERIC_NVAL, BAD_TIMESTAMP,
        /**
         * Not a check of the fields: the concept path reads as the consents variable once the loader has normalized it, but is not written
         * exactly {@code \_consents\} or {@code µ_consentsµ} (whitespace, a missing or doubled delimiter, U+FFFD, or the consent as a last
         * segment). The partition's loader would read the row as a consents row; the router grants nothing for it.
         */
        NON_CANONICAL_PATH,
        /** Not a check of the fields: the line could not be parsed as CSV and mentions the consents variable. */
        UNPARSABLE_LINE
    }

    /** The allConcepts contract: patient_num, concept_path, nval, tval, timestamp. */
    static final int FIELDS = 5;

    private static final Pattern TIMESTAMP = Pattern.compile("-?[0-9]*");

    // "uuuu-MM-dd HH:mm:ss" with an optional fraction of 1 to 9 digits, as in the splitter and the ingest-service TimestampParser
    private static final DateTimeFormatter SPACE_SEPARATED =
        new DateTimeFormatterBuilder().appendPattern("uuuu-MM-dd HH:mm:ss").optionalStart()
            .appendFraction(ChronoField.NANO_OF_SECOND, 1, 9, true).optionalEnd().toFormatter().withResolverStyle(ResolverStyle.STRICT);

    private ConsentRowCheck() {}

    /** The reason the row may not grant a consent, or null when it passes. */
    static Problem check(List<String> record) {
        if (record.size() != FIELDS) {
            return Problem.BAD_FIELD_COUNT;
        }
        if (parseInt(record.get(0)) == null) {
            return Problem.BAD_PATIENT_NUM;
        }
        String nval = record.get(2);
        if (!nval.isEmpty()) {
            return parsesAsDouble(nval) ? Problem.NUMERIC_NVAL : Problem.BAD_NVAL;
        }
        return validTimestamp(record.get(4)) ? null : Problem.BAD_TIMESTAMP;
    }

    /** The patient id of a row that passed, parsed as the splitter does (untrimmed), or null. */
    static Integer parseInt(String s) {
        if (s == null || s.isEmpty() || s.length() > 11) {
            return null;
        }
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static boolean parsesAsDouble(String s) {
        try {
            Double.parseDouble(s.trim());
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    static boolean validTimestamp(String ts) {
        if (TIMESTAMP.matcher(ts).matches()) {
            if (ts.isEmpty()) {
                return true;
            }
            try {
                Long.parseLong(ts);
                return true;
            } catch (NumberFormatException e) {
                return false;
            }
        }
        try {
            return toInstant(ts) != null;
        } catch (ArithmeticException e) {
            return false; // beyond what a 64-bit millisecond count holds
        }
    }

    private static Instant toInstant(String s) {
        for (int form = 0; form < 3; form++) {
            try {
                Instant instant = switch (form) {
                    case 0 -> Instant.parse(s);
                    case 1 -> parseZoneless(s);
                    default -> LocalDate.parse(s, DateTimeFormatter.ISO_LOCAL_DATE).atStartOfDay().toInstant(ZoneOffset.UTC);
                };
                instant.toEpochMilli(); // throws ArithmeticException beyond a long
                return instant;
            } catch (DateTimeParseException e) {
                // next form
            }
        }
        return null;
    }

    private static Instant parseZoneless(String s) {
        try {
            return LocalDateTime.parse(s, SPACE_SEPARATED).toInstant(ZoneOffset.UTC);
        } catch (DateTimeParseException e) {
            return LocalDateTime.parse(s, DateTimeFormatter.ISO_LOCAL_DATE_TIME).toInstant(ZoneOffset.UTC);
        }
    }
}
