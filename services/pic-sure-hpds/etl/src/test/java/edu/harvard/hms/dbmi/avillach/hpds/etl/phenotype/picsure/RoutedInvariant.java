package edu.harvard.hms.dbmi.avillach.hpds.etl.phenotype.picsure;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.time.temporal.ChronoField;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The access-control invariant of the routed output, written from the specification and <b>independent of {@link ConsentRouter}</b>: it
 * shares no parsing, segmenting, study derivation, folding or consent-row classification with it (it reads the input with commons-csv
 * itself). The only router-side definition it uses is {@link CsvObservationProducer#normalizeConceptPath}, because that is what the
 * partition's loader stores, which is the point of the check.
 *
 * <p>Facts derived from the input: <ul> <li>a <b>grant</b>: a row written exactly {@code \_consents\} or {@code µ_consentsµ}, with exactly 5
 * fields, an integer patient id as written, an empty nval, a blank/integer/ISO timestamp and a non-blank trimmed text value, gives its
 * patient that consent;</li> <li>a <b>consents study</b>: for every row whose path the loader reads as {@code \_consents\} (also a rejected one, one
 * with a doubtful path, a valid or an invalid name), the study of its text value (the part before the first dot; for a row with fewer than
 * 5 fields every field from the third on), in its exact spelling and in a folded spelling (U+FFFD, whitespace, control and format
 * characters removed, lower case).</li> </ul>
 *
 * <p>Asserted for every routed row, with its node taken from the <b>normalized</b> path (depth 1, as the loader stores it): <ul> <li>(a)
 * when the node folds to any consents study, the row lies only in a partition whose exact study equals the exact node, and the patient
 * holds that partition through a grant;</li> <li>(b) no row whose node folds to a consents study is routed as a global row (to a partition
 * of another exact study);</li> <li>(R1) the path starts with a delimiter (the text before the first one is empty), (R2) the study node
 * (depth 1 of the normalized path) is not empty (a row without a study node to read would be stored with its study in a place no reader
 * looks at);</li> <li>every row lies in a partition the patient holds through a grant;</li> <li>a row that reads as the
 * consents variable in the loader is routed only when written in one of the two canonical spellings.</li> </ul> A line that cannot be
 * parsed and mentions the consents variable has an unknown study: the invariant does not hold for such an input, and the run is refused.
 */
final class RoutedInvariant {

    private static final Pattern PATIENT = Pattern.compile("[+-]?[0-9]{1,10}");
    private static final Pattern PLAIN_NUMBER = Pattern.compile("[-+0-9.:]*");
    private static final Pattern INTEGER_TIMESTAMP = Pattern.compile("-?[0-9]*");
    private static final DateTimeFormatter SPACE_TIME = new DateTimeFormatterBuilder().appendPattern("uuuu-MM-dd HH:mm:ss").optionalStart()
        .appendFraction(ChronoField.NANO_OF_SECOND, 1, 9, true).optionalEnd().toFormatter().withResolverStyle(ResolverStyle.STRICT);

    private RoutedInvariant() {}

    /** The facts of an input, derived independently. */
    private record Facts(Set<String> foldedStudies, Map<Integer, Set<String>> grants) {
    }

    /** @return the number of routed rows checked */
    static int assertHolds(Path input, Path routedDir) throws IOException {
        Facts facts = derive(input);
        int checked = 0;
        if (!Files.isDirectory(routedDir)) {
            return 0;
        }
        try (Stream<Path> files = Files.list(routedDir)) {
            for (Path file : files.filter(p -> p.getFileName().toString().endsWith("-allConcepts.csv")).toList()) {
                String name = file.getFileName().toString();
                String partition = name.substring(0, name.length() - "-allConcepts.csv".length());
                String partitionStudy = beforeFirstDot(partition);
                for (String line : Files.readAllLines(file)) {
                    List<String> f = fields(line);
                    assertNotNull(f, "a routed line must parse: " + line);
                    assertEquals(5, f.size(), line);
                    String where = partition + ": " + line.replace("\uFFFD", "<FFFD>");
                    int patient = Integer.parseInt(f.get(0).trim());
                    assertTrue(facts.grants().getOrDefault(patient, Set.of()).contains(partition), "no valid canonical consent: " + where);

                    String path = f.get(1);
                    String normalized = CsvObservationProducer.normalizeConceptPath(path);
                    assertTrue(
                        path.startsWith("\\") || path.startsWith("µ"), "(R1) a routed row whose path does not start with a delimiter: " + where
                    );
                    assertTrue(normalized.startsWith("\\"), "(R1) a routed row whose normalized path has no leading delimiter: " + where);
                    String[] parts = normalized.split("\\\\", -1);
                    String node = parts[1];
                    assertFalse(node.isEmpty(), "(R2) a routed row whose normalized path has an empty study node (depth 1): " + where);
                    if (facts.foldedStudies().contains(fold(node))) {
                        boolean routedAsGlobal = !node.equals(partitionStudy);
                        assertFalse(routedAsGlobal, "(b) a row whose node folds to a consents study is routed as a global row: " + where);
                        assertEquals(node, partitionStudy, "(a) a study row outside the partition of its exact study: " + where);
                    }
                    if (readsAsConsentsVariable(path, f.get(3))) {
                        assertTrue(
                            path.equals("\\_consents\\") || path.equals("µ_consentsµ"), "a non-canonical consents row was routed: " + where
                        );
                    }
                    checked++;
                }
            }
        }
        return checked;
    }

    private static Facts derive(Path input) throws IOException {
        Set<String> folded = new HashSet<>();
        Map<Integer, Set<String>> grants = new HashMap<>();
        boolean first = true;
        for (String line : Files.readAllLines(input)) {
            if (first && line.isBlank()) {
                continue;
            }
            boolean wasFirst = first;
            first = false;
            if (line.isEmpty()) {
                continue;
            }
            List<String> f = fields(line);
            if (wasFirst && f != null && isHeader(f)) {
                continue;
            }
            if (f == null) {
                assertFalse(line.replace("\uFFFD", "").contains("_consents"), "an unparsable consents line has an unknown study: " + line);
                continue;
            }
            if (f.size() < 2 || !readsAsConsentsVariable(f.get(1), f.size() > 3 ? f.get(3) : "")) {
                continue; // not a consents row as the loader reads the path (a row under \_consents\x\ is an ordinary variable)
            }
            List<String> values = new ArrayList<>();
            if (f.size() >= 5) {
                values.add(f.get(3));
            } else {
                for (int i = 2; i < f.size(); i++) {
                    if (!PLAIN_NUMBER.matcher(f.get(i).trim()).matches()) {
                        values.add(f.get(i));
                    }
                }
            }
            for (String value : values) {
                for (String text : List.of(value.trim(), value.trim().replace("\uFFFD", ""))) {
                    if (!text.isEmpty() && !fold(text).isEmpty()) {
                        folded.add(fold(text));
                    }
                }
            }
            boolean canonical = f.get(1).equals("\\_consents\\") || f.get(1).equals("µ_consentsµ");
            if (canonical && f.size() == 5 && PATIENT.matcher(f.get(0)).matches() && f.get(2).isEmpty() && timestampOk(f.get(4))
                && !f.get(3).trim().isEmpty()) {
                try {
                    grants.computeIfAbsent(Integer.parseInt(f.get(0)), k -> new HashSet<>()).add(f.get(3).trim());
                } catch (NumberFormatException e) {
                    // does not fit an int: not a patient id
                }
            }
        }
        return new Facts(folded, grants);
    }

    /** The line as one CSV record by commons-csv itself, or null when it is not exactly one. */
    private static List<String> fields(String line) {
        try (CSVParser parser = CSVParser.parse(line, CSVFormat.DEFAULT)) {
            List<CSVRecord> records = parser.getRecords();
            if (records.size() != 1) {
                return null;
            }
            List<String> out = new ArrayList<>();
            records.get(0).forEach(out::add);
            return out;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private static boolean isHeader(List<String> f) {
        return f.size() >= 4 && Set.of("patient_num", "subject_id", "patient_id").contains(f.get(0).trim().toLowerCase(Locale.ROOT))
            && Set.of("concept_path", "conceptpath", "concept_cd", "concept").contains(f.get(1).trim().toLowerCase(Locale.ROOT));
    }

    /** Blank, an integer that fits a long, or an ISO date, date-time or instant (Z or offset). */
    private static boolean timestampOk(String ts) {
        if (INTEGER_TIMESTAMP.matcher(ts).matches()) {
            try {
                if (!ts.isEmpty()) {
                    Long.parseLong(ts);
                }
                return true;
            } catch (NumberFormatException e) {
                return false;
            }
        }
        try {
            Instant.parse(ts);
            return true;
        } catch (DateTimeParseException e) {
            // next form
        }
        try {
            LocalDateTime.parse(ts, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
            return true;
        } catch (DateTimeParseException e) {
            // next form
        }
        try {
            LocalDateTime.parse(ts, SPACE_TIME);
            return true;
        } catch (DateTimeParseException e) {
            // next form
        }
        try {
            LocalDate.parse(ts, DateTimeFormatter.ISO_LOCAL_DATE);
            return true;
        } catch (DateTimeParseException e) {
            return false;
        }
    }

    private static String beforeFirstDot(String s) {
        int dot = s.indexOf('.');
        return dot < 0 ? s : s.substring(0, dot);
    }

    /** U+FFFD and every whitespace, control, separator and format character removed; the part before the first dot; lower case. */
    private static String fold(String s) {
        StringBuilder kept = new StringBuilder();
        s.codePoints().forEach(cp -> {
            int type = Character.getType(cp);
            boolean blank = type == Character.CONTROL || type == Character.FORMAT || type == Character.SPACE_SEPARATOR
                || type == Character.LINE_SEPARATOR || type == Character.PARAGRAPH_SEPARATOR || Character.isWhitespace(cp);
            if (cp != 0xFFFD && !blank) {
                kept.appendCodePoint(cp);
            }
        });
        return beforeFirstDot(kept.toString()).toLowerCase(Locale.ROOT);
    }

    /**
     * The path as the partition's loader reads it ({@link CsvObservationProducer#normalizeConceptPath}, then the last segment equal to the
     * text value dropped) is the consents variable, before or after that drop.
     */
    private static boolean readsAsConsentsVariable(String path, String tval) {
        String normalized = CsvObservationProducer.normalizeConceptPath(path);
        if (normalized.equals("\\_consents\\")) {
            return true;
        }
        String text = tval.trim().replace("\uFFFD", "");
        if (!text.isEmpty() && normalized.endsWith("\\" + text + "\\")) {
            return normalized.substring(0, normalized.lastIndexOf('\\', normalized.length() - 2) + 1).equals("\\_consents\\");
        }
        return false;
    }
}
