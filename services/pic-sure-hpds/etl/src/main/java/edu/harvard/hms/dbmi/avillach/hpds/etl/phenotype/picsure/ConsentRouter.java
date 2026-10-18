package edu.harvard.hms.dbmi.avillach.hpds.etl.phenotype.picsure;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * Splits one combined allConcepts CSV (every study plus the global variables, the input of production hpds-ingest) into one CSV per
 * consent, the files {@code PICSURELoader} then loads as partitions. The rules are those of the {@code split-allconcepts-by-consent} job of
 * pic-sure-hpds-etl with {@code --globals=by-patient --partition-naming=suffix} ({@link #RULES_MATCH}), and are checked against it by the
 * vectors in {@code src/test/resources/consent-vectors}:
 *
 * <p>Pass 1 reads the file and records, for each patient, the consent strings of the rows whose concept path is {@code \_consents\} (the
 * consent is the trimmed text value). Pass 2 takes the path node at depth 1 ({@code \phs000000\...} is {@code phs000000}; {@code µ} is a
 * delimiter too): <ul> <li>a node that is the study of any consent string ({@code phs000000.c1} has the study {@code phs000000}, a string
 * without a dot is its own study) makes the row a <b>study row</b>, written to every consent of the patient for that study. A patient
 * without a consent for that study: the row is dropped and counted ({@link FailureReason#NO_CONSENT}); </li> <li>any other node is a
 * <b>global row</b>, copied to every consent of the patient (also the {@code \_consents\} rows themselves, and a {@code phsNNNNNN} node
 * that has no consent at all, which is only reported). A patient without any consent: dropped and counted; </li> <li>a consent value that
 * cannot name a partition (it must match {@link PICSURELoader#PARTITION_NAME}) is dropped for every patient; a row whose consents are all
 * dropped is not written ({@link FailureReason#INVALID_CONSENT_NAME}), a row with other consents only loses the copy for the dropped
 * one;</li> <li>a row that cannot be routed (fewer than 4 columns, a patient id that is missing or not an integer, a header row that is not
 * the first record) is counted and recorded with its own reason.</li> </ul>
 *
 * <p>Rows are written unchanged in content and in input order, every field quoted. The routing reads fields as they are (no trimming); only
 * the patient id is trimmed, as the loader does.
 *
 * <p>Differences from the splitter job, none of which changes where a well-formed row goes: it needs exactly 5 fields (here at least 4),
 * and rejects a row with an unparseable value or timestamp, which here is routed and left to the partition's loader; it accepts partition
 * names with spaces or {@code ..} that the loader's name rule refuses; its first row is a header when column 0 is not an integer, here when
 * it has the known header names (any other first row that is not a data row is an unroutable row).
 */
final class ConsentRouter {

    private static final Logger log = LoggerFactory.getLogger(ConsentRouter.class);

    /** The commit of pic-sure-hpds-etl whose split-allconcepts-by-consent rules these are. */
    static final String RULES_MATCH = "pic-sure-hpds-etl@6416d91";
    static final String CONSENTS_PATH = "\\_consents\\";

    /** Individually recorded rows per reason; the rest are counted and summarized in one record. */
    static final int MAX_RECORDS_PER_REASON = 1000;
    private static final int MAX_OPEN_WRITERS = 128;
    private static final Pattern STUDY_ID = Pattern.compile("phs[0-9]{6}");
    private static final char MU = 'µ';

    private final Path input;
    private final String runId;
    private FailureSink failures;

    // Pass 1
    private final Map<String, Consent> consentsByValue = new HashMap<>();
    private final Map<Integer, Consent[]> consentsByPatient = new HashMap<>();
    private final Set<String> studies = new HashSet<>();
    private boolean learned;
    private boolean headerSkipped;
    private long blankConsentRows;

    // Pass 2
    private final Map<String, long[]> partitionLines = new TreeMap<>();
    private final Map<FailureReason, long[]> reasonCounts = new EnumMap<>(FailureReason.class);
    private final Map<String, long[]> droppedConsentRows = new TreeMap<>();
    private final Set<Integer> patientsWithoutConsent = new HashSet<>();
    private final Set<String> studyNodesWithoutConsents = new TreeSet<>();
    private long rowsRead;
    private long studyRows;
    private long studyRowCopies;
    private long globalRows;
    private long globalRowCopies;
    private long noConsentStudyRows;
    private long noConsentGlobalRows;
    private long invalidConsentRows;
    private long unroutableRows;
    private long unroutableCrashClassRows;

    /** A consent string and what the router decided about it. */
    private record Consent(String value, String study, String problem) {
        boolean valid() {
            return problem == null;
        }
    }

    /** A consent value that cannot name a partition; {@code rows} counts the row copies meant for it that were not written. */
    record DroppedConsent(String value, String problem, long rows) {
    }

    /** What the routing did, for the run summary. */
    record Counts(
        boolean headerSkipped, long rowsRead, long rowsRouted, long studyRows, long studyRowCopies, long globalRows, long globalRowCopies,
        long linesWritten, long noConsentRowsDropped, long noConsentStudyRows, long noConsentGlobalRows, long invalidConsentRows,
        long unroutableRows, long unroutableCrashClassRows, Map<String, Long> unroutableByReason, long patientsWithConsents,
        long patientsWithoutConsent, Map<String, Long> rowsPerPartition, List<DroppedConsent> invalidConsentNames,
        List<String> studyNodesWithoutConsents, long blankConsentRows
    ) {
        /** Every row read is routed, dropped, rejected for its consents, or unroutable. */
        boolean balanced() {
            return rowsRead == rowsRouted + noConsentRowsDropped + invalidConsentRows + unroutableRows;
        }
    }

    ConsentRouter(Path input, String runId) {
        this.input = input;
        this.runId = runId;
    }

    // ---------------------------------------------------------------------------------------------------------------------------
    // Pass 1
    // ---------------------------------------------------------------------------------------------------------------------------

    /** Reads the whole input once and learns the consents of every patient. */
    void learnConsents() throws IOException {
        Map<Integer, TreeSet<String>> values = new HashMap<>();
        try (BufferedReader reader = openData(input); CSVParser parser = new CSVParser(reader, FORMAT)) {
            for (CSVRecord record : parser) {
                if (record.size() < 4 || !CONSENTS_PATH.equals(normalize(record.get(1)))) {
                    continue;
                }
                Integer patient = parsePatient(record.get(0));
                if (patient == null) {
                    continue;
                }
                String consent = record.get(3).trim();
                if (consent.isEmpty()) {
                    blankConsentRows++;
                    continue;
                }
                values.computeIfAbsent(patient, k -> new TreeSet<>()).add(consent);
            }
        }
        for (Map.Entry<Integer, TreeSet<String>> entry : values.entrySet()) {
            Consent[] consents = entry.getValue().stream().map(this::consent).toArray(Consent[]::new);
            consentsByPatient.put(entry.getKey(), consents);
        }
        learned = true;
        log.info(
            "Consents learned from {}: {} patient(s), {} consent value(s), {} study node(s), {} invalid name(s)", input.getFileName(),
            consentsByPatient.size(), consentsByValue.size(), studies.size(), invalidConsents().size()
        );
    }

    private Consent consent(String value) {
        return consentsByValue.computeIfAbsent(value, v -> {
            String problem = null;
            if (!PICSURELoader.PARTITION_NAME.matcher(v).matches()) {
                problem = "does not match " + PICSURELoader.PARTITION_NAME.pattern();
            } else if (v.contains("..")) {
                problem = "contains '..'";
            }
            String study = studyOf(v);
            studies.add(study);
            return new Consent(v, study, problem);
        });
    }

    /** The partition names the valid consents give, sorted; call after {@link #learnConsents()}. */
    List<String> partitionNames() {
        return consentsByValue.values().stream().filter(Consent::valid).map(Consent::value).sorted().toList();
    }

    private List<Consent> invalidConsents() {
        return consentsByValue.values().stream().filter(c -> !c.valid()).sorted((a, b) -> a.value().compareTo(b.value())).toList();
    }

    // ---------------------------------------------------------------------------------------------------------------------------
    // Pass 2
    // ---------------------------------------------------------------------------------------------------------------------------

    /**
     * Reads the input again and writes every row to the {@code <partition>-allConcepts.csv} of each consent it belongs to, under
     * {@code routedDir}; rows that cannot be routed or are dropped go to {@code failureSink}.
     */
    Counts route(Path routedDir, FailureSink failureSink) throws IOException {
        if (!learned) {
            throw new IllegalStateException("learnConsents() must run before route()");
        }
        this.failures = failureSink;
        Files.createDirectories(routedDir);
        try (
            Spool spool = new Spool(routedDir); BufferedReader reader = openData(input); CSVParser parser = new CSVParser(reader, FORMAT)
        ) {
            for (CSVRecord record : parser) {
                rowsRead++;
                routeRecord(record, spool);
            }
        }
        writeSummaryRecords();
        return counts();
    }

    private void routeRecord(CSVRecord record, Spool spool) throws IOException {
        if (record.size() < 4) {
            unroutable(record, FailureReason.MALFORMED_RECORD, null, "Record has only " + record.size() + " columns, expected at least 4");
            return;
        }
        String patientRaw = record.get(0);
        if (patientRaw == null || patientRaw.isBlank()) {
            unroutable(record, FailureReason.MISSING_PATIENT_ID, null, "PATIENT_NUM is null or empty");
            return;
        }
        Integer patient = parsePatient(patientRaw);
        if (patient == null) {
            unroutable(record, FailureReason.INVALID_PATIENT_ID, null, "Cannot parse as integer: " + patientRaw);
            return;
        }

        String path = record.get(1);
        String node = segment(path, 1);
        Consent[] consents = consentsByPatient.getOrDefault(patient, NO_CONSENTS);
        if (consents.length == 0) {
            patientsWithoutConsent.add(patient);
        }

        if (studies.contains(node)) {
            int written = 0;
            int invalid = 0;
            String line = null;
            for (Consent consent : consents) {
                if (!consent.study().equals(node)) {
                    continue;
                }
                if (consent.valid()) {
                    line = line != null ? line : format(record);
                    spool.write(consent.value(), line);
                    written++;
                } else {
                    invalid++;
                    droppedConsentRows.computeIfAbsent(consent.value(), k -> new long[1])[0]++;
                }
            }
            if (written > 0) {
                studyRows++;
                studyRowCopies += written;
            } else if (invalid > 0) {
                invalidConsent(record, patient);
            } else {
                noConsentStudyRows++;
                record(record, FailureReason.NO_CONSENT, patient, "The patient has no consent for the study " + node);
            }
            return;
        }

        // A global row: copied to every consent of the patient. A phsNNNNNN node that no consent names is one too, and only reported.
        if (node.length() == 9 && STUDY_ID.matcher(node).matches()) {
            studyNodesWithoutConsents.add(node);
        }
        if (consents.length == 0) {
            noConsentGlobalRows++;
            record(record, FailureReason.NO_CONSENT, patient, "The patient has no consent at all");
            return;
        }
        int written = 0;
        int invalid = 0;
        String line = null;
        for (Consent consent : consents) {
            if (consent.valid()) {
                line = line != null ? line : format(record);
                spool.write(consent.value(), line);
                written++;
            } else {
                invalid++;
                droppedConsentRows.computeIfAbsent(consent.value(), k -> new long[1])[0]++;
            }
        }
        if (written == 0 && invalid > 0) {
            invalidConsent(record, patient);
        } else {
            globalRows++;
            globalRowCopies += written;
        }
    }

    private void unroutable(CSVRecord record, FailureReason reason, Integer patient, String detail) {
        unroutableRows++;
        if (reason.crashesCsvLoader()) {
            unroutableCrashClassRows++;
        }
        record(record, reason, patient, detail);
    }

    private void invalidConsent(CSVRecord record, Integer patient) {
        invalidConsentRows++;
        record(record, FailureReason.INVALID_CONSENT_NAME, patient, "Every consent of the patient for this row has an invalid name");
    }

    /** Counts the row under its reason and writes the failure record, individually only up to {@link #MAX_RECORDS_PER_REASON}. */
    private void record(CSVRecord record, FailureReason reason, Integer patient, String detail) {
        long seen = ++reasonCounts.computeIfAbsent(reason, k -> new long[1])[0];
        if (seen > MAX_RECORDS_PER_REASON) {
            return;
        }
        failures.recordFailure(
            new FailureRecord(
                runId, input.getFileName().toString(), input.toString(), record.getRecordNumber(), record.size() > 0 ? record.get(0) : null,
                patient, record.size() > 1 ? record.get(1) : null, null, null, reason, detail
            )
        );
    }

    private void writeSummaryRecords() {
        reasonCounts.forEach((reason, count) -> {
            if (count[0] > MAX_RECORDS_PER_REASON) {
                failures.recordFailure(
                    new FailureRecord(
                        runId, input.getFileName().toString(), input.toString(), null, null, null, null, null, null, reason,
                        "Summary: " + count[0] + " rows in this file had this problem (" + reason.getDescription() + "). Only the first "
                            + MAX_RECORDS_PER_REASON + " are recorded individually; " + (count[0] - MAX_RECORDS_PER_REASON)
                            + " more were not."
                    )
                );
            }
        });
    }

    private Counts counts() {
        Map<String, Long> rowsPerPartition = new TreeMap<>();
        partitionLines.forEach((name, n) -> rowsPerPartition.put(name, n[0]));
        Map<String, Long> unroutable = new TreeMap<>();
        for (
            FailureReason reason : List
                .of(FailureReason.MALFORMED_RECORD, FailureReason.MISSING_PATIENT_ID, FailureReason.INVALID_PATIENT_ID)
        ) {
            long[] n = reasonCounts.get(reason);
            if (n != null) {
                unroutable.put(reason.name(), n[0]);
            }
        }
        List<DroppedConsent> dropped = new ArrayList<>();
        for (Consent consent : invalidConsents()) {
            long[] rows = droppedConsentRows.get(consent.value());
            dropped.add(new DroppedConsent(consent.value(), consent.problem(), rows == null ? 0 : rows[0]));
        }
        long linesWritten = rowsPerPartition.values().stream().mapToLong(Long::longValue).sum();
        return new Counts(
            headerSkipped, rowsRead, studyRows + globalRows, studyRows, studyRowCopies, globalRows, globalRowCopies, linesWritten,
            noConsentStudyRows + noConsentGlobalRows, noConsentStudyRows, noConsentGlobalRows, invalidConsentRows, unroutableRows,
            unroutableCrashClassRows, unroutable, consentsByPatient.size(), patientsWithoutConsent.size(), rowsPerPartition, dropped,
            List.copyOf(studyNodesWithoutConsents), blankConsentRows
        );
    }

    // ---------------------------------------------------------------------------------------------------------------------------
    // Reading and writing
    // ---------------------------------------------------------------------------------------------------------------------------

    private static final Consent[] NO_CONSENTS = new Consent[0];

    /** Fields as they are: the router decides on the untrimmed text, the partition's loader trims. */
    private static final CSVFormat FORMAT = CSVFormat.DEFAULT.withIgnoreEmptyLines(true).withQuote('"');

    /** Opens the input past a header row (known header names, as {@link CsvObservationProducer} decides). */
    private BufferedReader openData(Path file) throws IOException {
        boolean header = false;
        try (BufferedReader probe = CsvObservationProducer.openReader(file)) {
            String line;
            while ((line = probe.readLine()) != null) {
                if (!line.isBlank()) {
                    header = CsvObservationProducer.isHeaderLine(line);
                    break;
                }
            }
        }
        BufferedReader reader = CsvObservationProducer.openReader(file);
        try {
            if (header) {
                String line;
                do {
                    line = reader.readLine(); // leading blank lines, then the header itself
                } while (line != null && line.isBlank());
            }
        } catch (IOException | RuntimeException e) {
            reader.close();
            throw e;
        }
        headerSkipped = header;
        return reader;
    }

    /** The patient id as the loader reads it (trimmed), or null. */
    private static Integer parsePatient(String raw) {
        if (raw == null) {
            return null;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** {@code µ} is the legacy path delimiter: both compare and split alike. */
    static String normalize(String path) {
        return path.replace(MU, '\\');
    }

    /** The segment at {@code depth} of a path delimited by backslash or µ; the part before the first delimiter is depth 0. */
    static String segment(String path, int depth) {
        int start = 0;
        for (int i = 0; i < depth; i++) {
            int next = indexOfDelimiter(path, start);
            if (next < 0) {
                return "";
            }
            start = next + 1;
        }
        int end = indexOfDelimiter(path, start);
        return end < 0 ? path.substring(start) : path.substring(start, end);
    }

    private static int indexOfDelimiter(String s, int from) {
        for (int i = from; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' || c == MU) {
                return i;
            }
        }
        return -1;
    }

    /** {@code phs000000.c1} gives {@code phs000000}; a string without a dot is its own study. */
    static String studyOf(String consent) {
        int dot = consent.indexOf('.');
        return dot < 0 ? consent : consent.substring(0, dot);
    }

    /** Every field quoted, quotes doubled. */
    static String format(CSVRecord record) {
        StringBuilder sb = new StringBuilder(64);
        for (int i = 0; i < record.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append('"').append(record.get(i).replace("\"", "\"\"")).append('"');
        }
        return sb.toString();
    }

    /** One file per partition. Open writers are capped (least recently used closed); a reopened file is appended to, so order is kept. */
    private final class Spool implements AutoCloseable {
        private final Path dir;
        private final LinkedHashMap<String, BufferedWriter> open = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, BufferedWriter> eldest) {
                if (size() <= MAX_OPEN_WRITERS) {
                    return false;
                }
                try {
                    eldest.getValue().close();
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
                return true;
            }
        };

        Spool(Path dir) {
            this.dir = dir;
        }

        void write(String partition, String line) throws IOException {
            BufferedWriter writer = open.get(partition);
            if (writer == null) {
                Path file = dir.resolve(partition + PICSURELoader.ALL_CONCEPTS_SUFFIX);
                writer = Files.newBufferedWriter(
                    file, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND
                );
                open.put(partition, writer);
            }
            writer.write(line);
            writer.write('\n');
            partitionLines.computeIfAbsent(partition, k -> new long[1])[0]++;
        }

        @Override
        public void close() throws IOException {
            IOException first = null;
            for (BufferedWriter writer : open.values()) {
                try {
                    writer.close();
                } catch (IOException e) {
                    first = first != null ? first : e;
                }
            }
            open.clear();
            if (first != null) {
                throw first;
            }
        }
    }

    /** Case-insensitive duplicates among {@code names}: pairs of names that differ only by case. */
    static List<String> caseClashes(List<String> names) {
        Map<String, String> seen = new HashMap<>();
        List<String> clashes = new ArrayList<>();
        for (String name : names) {
            String other = seen.putIfAbsent(name.toLowerCase(Locale.ROOT), name);
            if (other != null) {
                clashes.add(other + " / " + name);
            }
        }
        return clashes;
    }
}
