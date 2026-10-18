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
import java.util.Arrays;
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
import java.util.function.LongConsumer;
import java.util.regex.Pattern;

/**
 * Splits one combined allConcepts CSV (every study plus the global variables, the input of production hpds-ingest) into one CSV per
 * consent, the files {@code PICSURELoader} then loads as partitions. The rules are those of the {@code split-allconcepts-by-consent} job of
 * pic-sure-hpds-etl with {@code --globals=by-patient --partition-naming=suffix} ({@link #RULES_MATCH}), and are checked against it by the
 * vectors in {@code src/test/resources/consent-vectors}:
 *
 * <p>Pass 1 reads the file and records, for each patient, the consent strings of the rows whose concept path is {@code \_consents\} (the
 * consent is the trimmed text value). <b>Only a row that passes {@link ConsentRowCheck} grants a consent</b>; a consents row that does not
 * (not 5 fields, a patient id that is not an integer as written, an nval that is not blank, a timestamp that is not blank, an integer or
 * ISO) grants nothing, is written to no partition, is counted by reason and counts against {@code maxRejectedRows} (default 0: the run is
 * refused before any partition loads, as the splitter's default {@code bad-rows=fail} does). Pass 2 takes the path node at depth 1
 * ({@code \phs000000\...} is {@code phs000000}; {@code µ} is a delimiter too): <ul> <li>a node that is the study of any consent string
 * ({@code phs000000.c1} has the study {@code phs000000}, a string without a dot is its own study) makes the row a <b>study row</b>, written
 * to every consent of the patient for that study. A patient without a consent for that study: the row is dropped and counted
 * ({@link FailureReason#NO_CONSENT}); </li> <li>any other node is a <b>global row</b>, copied to every consent of the patient (also the
 * {@code \_consents\} rows themselves, and a {@code phsNNNNNN} node that has no consent at all, which is only reported). A patient without
 * any consent: dropped and counted; </li> <li>a consent value that cannot name a partition (it must match
 * {@link PICSURELoader#PARTITION_NAME}) is dropped for every patient; a row whose consents are all dropped is not written
 * ({@link FailureReason#INVALID_CONSENT_NAME}), a row with other consents only loses the copy for the dropped one;</li> <li>a row whose
 * study node (depth 1) is not the same after {@link CsvObservationProducer#normalizeConceptPath} (the partition's loader trims segments and
 * removes U+FFFD: {@code \ phs000000\A\} would become a study row there, but is a global row here) is unroutable
 * ({@link FailureReason#INVALID_STUDY_NODE}), never global;</li> <li>a row that cannot be routed (not exactly 5 fields, the allConcepts
 * contract; a patient id that is missing or not an integer; a header row that is not the first line; a line that cannot be parsed as CSV)
 * is counted and recorded with its own reason. <b>Every row that is not routed counts against {@code maxRejectedRows}</b> (default 0),
 * because it is data that does not reach HPDS.</li> </ul>
 *
 * <p>The input is read line by line and every line is parsed as one record of its own (allConcepts has no line breaks inside a field), so
 * an unterminated quote costs that line, not the lines after it. A line that cannot be parsed and mentions {@code _consents} is a rejected
 * consents row (it grants nothing), any other one is a {@link FailureReason#MALFORMED_RECORD}. Line numbers in the failure records are the
 * physical line numbers of the input.
 *
 * <p>Rows are written unchanged in content and in input order, every field quoted. The routing reads fields as they are (no trimming); only
 * the patient id is trimmed, as the loader does.
 *
 * <p>Differences from the splitter job, none of which changes where a well-formed row goes: it rejects a data row with an unparseable value
 * or timestamp, which here is routed and left to the partition's loader (a consents row is judged as in the splitter); it reads a quoted
 * field across line breaks, here every line stands alone; it accepts partition names with spaces or {@code ..} that the loader's name rule
 * refuses; its first row is a header when column 0 is not an integer, here when it has the known header names (any other first row that is
 * not a data row is an unroutable row).
 *
 * <p><b>One deliberate difference that changes who gets access:</b> a consents row with a non-blank nval does not grant, also when the
 * number is valid. The splitter ignores the nval and grants from the text value; the loader would store that number while routing by the
 * text. The row is rejected ({@link ConsentRowCheck.Problem#NUMERIC_NVAL}) and the splitter should adopt the same rule. A second rule the
 * splitter lacks: the study-node check against the loader's path normalization above.
 *
 * <p>Memory of pass 1: one entry per patient with consents (an {@code Integer} key, a map node and a reference to a shared, interned set of
 * consents: about 60 bytes, measured with 2 million patients), so about 60 MB of heap per million patients plus a few KB for the distinct
 * consent sets. Pass 2 holds the patients that have no consent (a set of boxed ids, about 50 bytes each) and at most {@code maxOpenWriters}
 * open files.
 */
final class ConsentRouter {

    private static final Logger log = LoggerFactory.getLogger(ConsentRouter.class);

    /** The commit of pic-sure-hpds-etl whose split-allconcepts-by-consent rules these are. */
    static final String RULES_MATCH = "pic-sure-hpds-etl@6416d91";
    static final String CONSENTS_PATH = "\\_consents\\";

    /** Individually recorded rows per reason; the rest are counted and summarized in one record. */
    static final int MAX_RECORDS_PER_REASON = 1000;
    /** Test seam: called with the number of spool lines written so far; may throw {@link UncheckedIOException} to simulate a full disk. */
    static volatile LongConsumer spoolWriteHook;

    private static final Pattern STUDY_ID = Pattern.compile("phs[0-9]{6}");
    private static final char MU = 'µ';

    private final Path input;
    private final String runId;
    private final int maxOpenWriters;
    private FailureSink failures;

    // Pass 1
    private final Map<String, Consent> consentsByValue = new HashMap<>();
    private final Map<Integer, ConsentSet> consentsByPatient = new HashMap<>();
    private final Map<ConsentSet, ConsentSet> internedSets = new HashMap<>();
    private final Set<String> studies = new HashSet<>();
    private boolean learned;
    private boolean headerSkipped;
    private long blankConsentRows;
    private long pass1Rows;
    private final Map<ConsentRowCheck.Problem, long[]> pass1Rejected = new EnumMap<>(ConsentRowCheck.Problem.class);

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
    private long consentRowsRejected;
    private final Map<ConsentRowCheck.Problem, long[]> consentRejectedByProblem = new EnumMap<>(ConsentRowCheck.Problem.class);
    private long spoolWriterEvictions;
    private long spoolLinesWritten;

    /** A consent string and what the router decided about it. */
    private record Consent(String value, String study, String problem) {
        boolean valid() {
            return problem == null;
        }
    }

    /** The distinct consents of one patient, sorted by value; equal sets are one object (interned), so a million patients share a few. */
    private static final class ConsentSet {
        final Consent[] items;
        private final int hash;

        ConsentSet(Consent[] items) {
            this.items = items;
            this.hash = Arrays.hashCode(items);
        }

        /** This set with {@code consent} added; {@code this} when it is in it already. */
        ConsentSet with(Consent consent) {
            int at = 0;
            while (at < items.length && items[at].value().compareTo(consent.value()) < 0) {
                at++;
            }
            if (at < items.length && items[at] == consent) {
                return this;
            }
            Consent[] grown = new Consent[items.length + 1];
            System.arraycopy(items, 0, grown, 0, at);
            grown[at] = consent;
            System.arraycopy(items, at, grown, at + 1, items.length - at);
            return new ConsentSet(grown);
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof ConsentSet set) || set.hash != hash || set.items.length != items.length) {
                return false;
            }
            for (int i = 0; i < items.length; i++) {
                if (items[i] != set.items[i]) { // consents are canonical per value
                    return false;
                }
            }
            return true;
        }

        @Override
        public int hashCode() {
            return hash;
        }
    }

    private static final ConsentSet NO_CONSENT_SET = new ConsentSet(new Consent[0]);

    /** A consent value that cannot name a partition; {@code rows} counts the row copies meant for it that were not written. */
    record DroppedConsent(String value, String problem, long rows) {
    }

    /** What the routing did, for the run summary. */
    record Counts(
        boolean headerSkipped, long rowsRead, long rowsRouted, long studyRows, long studyRowCopies, long globalRows, long globalRowCopies,
        long linesWritten, long noConsentRowsDropped, long noConsentStudyRows, long noConsentGlobalRows, long invalidConsentRows,
        long unroutableRows, long unroutableCrashClassRows, Map<String, Long> unroutableByReason, long patientsWithConsents,
        long patientsWithoutConsent, Map<String, Long> rowsPerPartition, List<DroppedConsent> invalidConsentNames,
        List<String> studyNodesWithoutConsents, long blankConsentRows, long consentRowsRejected,
        Map<String, Long> consentRowsRejectedByReason, long spoolWriterEvictions, int maxOpenWriters
    ) {
        /** Every row read is routed, dropped, rejected for its consents, a rejected consents row, or unroutable. */
        boolean balanced() {
            return rowsRead == rowsRouted + noConsentRowsDropped + invalidConsentRows + unroutableRows + consentRowsRejected;
        }

        /**
         * Rows lost that the run does not tolerate beyond {@code maxRejectedRows}: crash-class unroutable rows and rejected consents rows.
         */
        long refusedClassRows() {
            return unroutableCrashClassRows + consentRowsRejected;
        }
    }

    /** The input is not what pass 1 read. */
    static final class InputChangedException extends IOException {
        InputChangedException(String message) {
            super(message);
        }
    }

    ConsentRouter(Path input, String runId) {
        this(input, runId, 128);
    }

    ConsentRouter(Path input, String runId, int maxOpenWriters) {
        if (maxOpenWriters < 1) {
            throw new IllegalArgumentException("maxOpenWriters must be at least 1, got " + maxOpenWriters);
        }
        this.input = input;
        this.runId = runId;
        this.maxOpenWriters = maxOpenWriters;
    }

    // ---------------------------------------------------------------------------------------------------------------------------
    // Pass 1
    // ---------------------------------------------------------------------------------------------------------------------------

    /** Reads the whole input once and learns the consents of every patient. */
    void learnConsents() throws IOException {
        try (Lines lines = new Lines(input)) {
            for (Row record = lines.next(); record != null; record = lines.next()) {
                pass1Rows++;
                if (record.fields() == null) { // a line that cannot be parsed: if it might be a consents row, it grants nothing
                    if (mightBeConsentsRow(record.raw())) {
                        pass1Rejected.computeIfAbsent(ConsentRowCheck.Problem.UNPARSABLE_LINE, k -> new long[1])[0]++;
                    }
                    continue;
                }
                if (!isConsentsRow(record)) {
                    continue;
                }
                ConsentRowCheck.Problem problem = ConsentRowCheck.check(record.fields());
                if (problem != null) {
                    pass1Rejected.computeIfAbsent(problem, k -> new long[1])[0]++;
                    continue;
                }
                String consent = record.get(3).trim();
                if (consent.isEmpty()) {
                    blankConsentRows++;
                    continue;
                }
                Integer patient = ConsentRowCheck.parseInt(record.get(0)); // not null: the row passed the check
                ConsentSet current = consentsByPatient.getOrDefault(patient, NO_CONSENT_SET);
                ConsentSet grown = current.with(consent(consent));
                if (grown != current) {
                    consentsByPatient.put(patient, internedSets.computeIfAbsent(grown, k -> k));
                }
            }
        }
        learned = true;
        log.info(
            "Consents learned from {}: {} patient(s), {} consent value(s) in {} distinct set(s), {} study node(s), {} invalid name(s), "
                + "{} consents row(s) rejected",
            input.getFileName(), consentsByPatient.size(), consentsByValue.size(), internedSets.size(), studies.size(),
            invalidConsents().size(), pass1Rejected.values().stream().mapToLong(n -> n[0]).sum()
        );
    }

    /** A record of the consents variable, whatever its other fields are (a rejected one must not slip through as a global row). */
    private static boolean isConsentsRow(Row record) {
        if (record.fields() == null) {
            return false;
        }
        if (record.size() < 2) {
            return false;
        }
        String path = record.get(1);
        return path.length() == CONSENTS_PATH.length() && CONSENTS_PATH.equals(normalize(path));
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

    /** Patients with at least one consent; call after {@link #learnConsents()}. */
    int patientsWithConsents() {
        return consentsByPatient.size();
    }

    /** Different sets of consents among the patients: patients with the same consents share one set. */
    int distinctConsentSets() {
        return internedSets.size();
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
        try (Spool spool = new Spool(routedDir); Lines lines = new Lines(input)) {
            for (Row record = lines.next(); record != null; record = lines.next()) {
                rowsRead++;
                routeRecord(record, spool);
            }
        }
        writeSummaryRecords();
        checkPassesAgree();
        return counts();
    }

    /** Both passes judge the same rows with the same rules; a difference means the file moved under the loader. */
    private void checkPassesAgree() throws InputChangedException {
        if (rowsRead != pass1Rows) {
            throw new InputChangedException("the input changed while it was read: pass 1 read " + pass1Rows + " rows, pass 2 " + rowsRead);
        }
        for (ConsentRowCheck.Problem problem : ConsentRowCheck.Problem.values()) {
            long one = pass1Rejected.getOrDefault(problem, new long[1])[0];
            long two = consentRejectedByProblem.getOrDefault(problem, new long[1])[0];
            if (one != two) {
                throw new InputChangedException(
                    "the input changed while it was read: pass 1 rejected " + one + " consents row(s) as " + problem + ", pass 2 " + two
                );
            }
        }
    }

    private void routeRecord(Row record, Spool spool) throws IOException {
        if (record.fields() == null) {
            if (mightBeConsentsRow(record.raw())) {
                rejectConsentRow(record, ConsentRowCheck.Problem.UNPARSABLE_LINE);
            } else {
                unroutable(record, FailureReason.MALFORMED_RECORD, null, "The line cannot be parsed as CSV");
            }
            return;
        }
        if (isConsentsRow(record)) {
            ConsentRowCheck.Problem problem = ConsentRowCheck.check(record.fields());
            if (problem != null) {
                rejectConsentRow(record, problem);
                return;
            }
        }
        if (record.size() != ConsentRowCheck.FIELDS) {
            unroutable(
                record, FailureReason.MALFORMED_RECORD, null,
                "Record has " + record.size() + " fields, the allConcepts contract is exactly " + ConsentRowCheck.FIELDS
            );
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
        if (studyNodeChangesOnNormalization(path, node)) {
            unroutable(
                record, FailureReason.INVALID_STUDY_NODE, patient,
                "The study node '" + node + "' is '" + segment(CsvObservationProducer.normalizeConceptPath(path), 1)
                    + "' once the path is normalized; the row is not routed (it would be read as a global or as a study row)"
            );
            return;
        }
        Consent[] consents = consentsByPatient.getOrDefault(patient, NO_CONSENT_SET).items;
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
                    line = line != null ? line : format(record.fields());
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
                line = line != null ? line : format(record.fields());
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

    /**
     * True when the path node at depth 1 is another text once {@link CsvObservationProducer#normalizeConceptPath} has trimmed the segments
     * and removed U+FFFD. A node without leading or trailing whitespace and without U+FFFD cannot change (every segment is cut out the same
     * way before and after), so the path is only normalized when its node looks otherwise.
     */
    static boolean studyNodeChangesOnNormalization(String path, String node) {
        if (isNormalizedSegment(node)) {
            return false;
        }
        return !node.equals(segment(CsvObservationProducer.normalizeConceptPath(path), 1));
    }

    private static boolean isNormalizedSegment(String segment) {
        int n = segment.length();
        if (n > 0 && (segment.charAt(0) <= ' ' || segment.charAt(n - 1) <= ' ')) {
            return false;
        }
        return segment.indexOf('\uFFFD') < 0;
    }

    private void rejectConsentRow(Row record, ConsentRowCheck.Problem problem) {
        consentRowsRejected++;
        consentRejectedByProblem.computeIfAbsent(problem, k -> new long[1])[0]++;
        record(record, FailureReason.CONSENT_ROW_REJECTED, null, "The row grants no consent and is written nowhere: " + problem);
    }

    /** A line that cannot be parsed may be a consents row; fail closed on anything that mentions the consents variable. */
    private static boolean mightBeConsentsRow(String raw) {
        return raw.contains("_consents");
    }

    /** A row that is not routed is a lost row: all of them count against {@code maxRejectedRows} (default 0). */
    private void unroutable(Row record, FailureReason reason, Integer patient, String detail) {
        unroutableRows++;
        unroutableCrashClassRows++;
        record(record, reason, patient, detail);
    }

    private void invalidConsent(Row record, Integer patient) {
        invalidConsentRows++;
        record(record, FailureReason.INVALID_CONSENT_NAME, patient, "Every consent of the patient for this row has an invalid name");
    }

    /** Counts the row under its reason and writes the failure record, individually only up to {@link #MAX_RECORDS_PER_REASON}. */
    private void record(Row record, FailureReason reason, Integer patient, String detail) {
        long seen = ++reasonCounts.computeIfAbsent(reason, k -> new long[1])[0];
        if (seen > MAX_RECORDS_PER_REASON) {
            return;
        }
        failures.recordFailure(
            new FailureRecord(
                runId, input.getFileName().toString(), input.toString(), record.line(), record.size() > 0 ? record.get(0) : null, patient,
                record.size() > 1 ? record.get(1) : null, null, null, reason, detail
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
            FailureReason reason : List.of(
                FailureReason.MALFORMED_RECORD, FailureReason.MISSING_PATIENT_ID, FailureReason.INVALID_PATIENT_ID,
                FailureReason.INVALID_STUDY_NODE
            )
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
        Map<String, Long> rejectedConsentRows = new TreeMap<>();
        consentRejectedByProblem.forEach((problem, n) -> rejectedConsentRows.put(problem.name(), n[0]));
        return new Counts(
            headerSkipped, rowsRead, studyRows + globalRows, studyRows, studyRowCopies, globalRows, globalRowCopies, linesWritten,
            noConsentStudyRows + noConsentGlobalRows, noConsentStudyRows, noConsentGlobalRows, invalidConsentRows, unroutableRows,
            unroutableCrashClassRows, unroutable, consentsByPatient.size(), patientsWithoutConsent.size(), rowsPerPartition, dropped,
            List.copyOf(studyNodesWithoutConsents), blankConsentRows, consentRowsRejected, rejectedConsentRows, spoolWriterEvictions,
            maxOpenWriters
        );
    }

    // ---------------------------------------------------------------------------------------------------------------------------
    // Reading and writing
    // ---------------------------------------------------------------------------------------------------------------------------

    /** Fields as they are: the router decides on the untrimmed text, the partition's loader trims. */
    private static final CSVFormat FORMAT = CSVFormat.DEFAULT.withQuote('"');

    /**
     * One physical line of the input. {@code fields} is null when the line cannot be parsed as one CSV record. allConcepts has no line
     * breaks inside a field (the runner refuses them), so every line is a record of its own and a broken line cannot swallow its
     * successors.
     */
    record Row(long line, String raw, List<String> fields) {
        int size() {
            return fields == null ? 0 : fields.size();
        }

        String get(int i) {
            return fields.get(i);
        }
    }

    /** Reads the input line by line past a header row (known header names, as {@link CsvObservationProducer} decides). */
    private final class Lines implements AutoCloseable {
        private final BufferedReader reader;
        private long lineNumber;
        private boolean first = true;

        Lines(Path file) throws IOException {
            this.reader = CsvObservationProducer.openReader(file);
        }

        /** The next row, or null at the end. Empty lines are skipped (leading blank ones too); the header row is skipped. */
        Row next() throws IOException {
            String line;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                if (first) {
                    if (line.isBlank()) {
                        continue;
                    }
                    first = false;
                    if (CsvObservationProducer.isHeaderLine(line)) {
                        headerSkipped = true;
                        continue;
                    }
                } else if (line.isEmpty()) {
                    continue;
                }
                return new Row(lineNumber, line, parseLine(line));
            }
            return null;
        }

        @Override
        public void close() throws IOException {
            reader.close();
        }
    }

    /** The fields of one line as they are (the router decides on the untrimmed text), or null if it is not one CSV record. */
    static List<String> parseLine(String line) {
        if (line.indexOf('"') < 0) { // no quotes: a plain split, exactly what the CSV parser gives
            List<String> fields = new ArrayList<>(8);
            int from = 0;
            for (int comma = line.indexOf(','); comma >= 0; comma = line.indexOf(',', from)) {
                fields.add(line.substring(from, comma));
                from = comma + 1;
            }
            fields.add(line.substring(from));
            return fields;
        }
        try (CSVParser parser = CSVParser.parse(line, FORMAT)) {
            java.util.Iterator<CSVRecord> records = parser.iterator();
            if (!records.hasNext()) {
                return null;
            }
            CSVRecord record = records.next();
            if (records.hasNext()) {
                return null;
            }
            List<String> fields = new ArrayList<>(record.size());
            record.forEach(fields::add);
            return fields;
        } catch (IOException | RuntimeException e) {
            return null;
        }
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
    static String format(List<String> fields) {
        StringBuilder sb = new StringBuilder(64);
        for (int i = 0; i < fields.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append('"').append(fields.get(i).replace("\"", "\"\"")).append('"');
        }
        return sb.toString();
    }

    /** One file per partition. Open writers are capped (least recently used closed); a reopened file is appended to, so order is kept. */
    private final class Spool implements AutoCloseable {
        private final Path dir;
        private final LinkedHashMap<String, BufferedWriter> open = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, BufferedWriter> eldest) {
                if (size() <= maxOpenWriters) {
                    return false;
                }
                try {
                    eldest.getValue().close();
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
                spoolWriterEvictions++;
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
            LongConsumer hook = spoolWriteHook;
            if (hook != null) {
                hook.accept(++spoolLinesWritten);
            }
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
