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
 * consent is the trimmed text value). A row is a consents row when its path is that variable <b>as the partition's loader reads it</b>:
 * after {@link CsvObservationProducer#normalizeConceptPath} and after the loader's rule that drops a last segment equal to the text value.
 * <b>Only a row written exactly {@code \_consents\} (or {@code µ_consentsµ}) that passes {@link ConsentRowCheck} grants a consent</b>;
 * every other consents row ({@link ConsentRowCheck.Problem#NON_CANONICAL_PATH}: {@code \_consents\ }, {@code \_consents},
 * {@code \_consents\phs000000.c1\} with that text value; not 5 fields, a patient id that is not an integer as written, an nval that is not
 * blank, a timestamp that is not blank, an integer or ISO) grants nothing, is written to no partition, is counted by reason and counts
 * against {@code maxRejectedConsentRows} (default 0: the run is refused before any partition loads). <b>A rejected consents row still
 * registers its study</b> (the study of its trimmed text value): a data row of that study of a patient without a valid consent of it is
 * dropped ({@link FailureReason#NO_CONSENT}), never treated as a global row. A line that cannot be parsed and mentions the consents
 * variable ({@link ConsentRowCheck.Problem#UNPARSABLE_LINE}) has an unknown study: the run is always refused, whatever the limits. Pass 2
 * takes the path node at depth 1 ({@code \phs000000\...} is {@code phs000000}; {@code µ} is a delimiter too): <ul> <li>a node that is the
 * study of any consent string ({@code phs000000.c1} has the study {@code phs000000}, a string without a dot is its own study) makes the row
 * a <b>study row</b>, written to every consent of the patient for that study. A patient without a consent for that study: the row is
 * dropped and counted ({@link FailureReason#NO_CONSENT}); </li> <li>any other node is a <b>global row</b>, copied to every consent of the
 * patient (also the valid {@code \_consents\} rows themselves, and a {@code phsNNNNNN} node that no consents row names at all, which is
 * only reported, as the splitter does). A patient without any consent: dropped and counted; </li> <li>a consent value that cannot name a
 * partition (it must match {@link PICSURELoader#PARTITION_NAME}) is dropped for every patient; a row whose consents are all dropped is not
 * written ({@link FailureReason#INVALID_CONSENT_NAME}), a row with other consents only loses the copy for the dropped one;</li> <li>a row
 * whose study node (depth 1) is not the same after {@link CsvObservationProducer#normalizeConceptPath} (the partition's loader trims
 * segments and removes U+FFFD: {@code \ phs000000\A\} would become a study row there, but is a global row here) is unroutable
 * ({@link FailureReason#INVALID_STUDY_NODE}), never global;</li> <li><b>folded studies</b>: every consents value (valid, invalid name or
 * rejected row) registers its study by its exact spelling and by {@link #foldStudy its folded key} (U+FFFD, blanks and case removed). A
 * data row whose node is no exact study but folds to a known one ({@code PHS999999}, {@code phs999999<U+FFFD>}, {@code phs999 999} next to
 * {@code phs999999.c1}) is unroutable ({@link FailureReason#INVALID_STUDY_NODE}), never global. Valid consents whose studies differ only by
 * such a folding ({@code PHS999999.c1}, {@code phs999999.c2}) refuse the run ({@link #studyFoldClashes()}). Rows under
 * {@code \_consents\<anything>\} that are no consents rows stay global and are counted ({@code consentsSubtreeRowsNotConsents});</li>
 * <li><b>path shape</b> (proposed contract rules R1 and R2): R1, the text before the first delimiter must be empty (the path starts with
 * {@code \} or {@code µ}); {@code phs999999\X\} is unroutable ({@link FailureReason#MALFORMED_CONCEPT_PATH}) and so is a consents-like path
 * with a leading text ({@code _consents\} never grants). <b>A path that mentions {@code _consents} (any case, also with U+FFFD inside) and
 * breaks R1 or R2 might be a consents row, so it is a rejected consents row, not a data row</b>
 * ({@link ConsentRowCheck.Problem#MALFORMED_CONCEPT_PATH}, {@link ConsentRowCheck.Problem#INVALID_STUDY_NODE}, against
 * {@code maxRejectedConsentRows}): it registers the study of its text value like any rejected consents row, so raising a limit never turns
 * the well-formed rows of that study into global rows. R2, the study node must not be empty: {@code \\phs999999\X\} is
 * {@link FailureReason#INVALID_STUDY_NODE}, as is a node that normalization changes or that folds to a study. A study id at any other depth
 * is no rule ({@code \_studies_consents\phs000000\} is a legitimate global);</li> <li>a row that cannot be routed (not exactly 5 fields,
 * the allConcepts contract; a patient id that is missing or not an integer; a header row that is not the first line; a line that cannot be
 * parsed as CSV) is counted and recorded with its own reason. <b>Every such row counts against {@code maxRejectedRows}</b> (default 0),
 * because it is data that does not reach HPDS.</li> </ul>
 *
 * <p>The input is read line by line and every line is parsed as one record of its own (allConcepts has no line breaks inside a field), so
 * an unterminated quote costs that line, not the lines after it. A line that cannot be parsed and mentions {@code _consents} is a consents
 * row that grants nothing and refuses the run, any other one is a {@link FailureReason#MALFORMED_RECORD}. Line numbers in the failure
 * records are the physical line numbers of the input.
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
 * text. The row is rejected ({@link ConsentRowCheck.Problem#NUMERIC_NVAL}) and the splitter should adopt the same rule. More rules the
 * splitter lacks, all fail closed: the study-node check against the loader's path normalization above, the canonical form of the consents
 * path, and the registration of the study of a rejected consents row.
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
    private static final int MAX_LISTED_LINES = 10;
    /** The path of the consents variable as the loader reads it, and its legacy form with µ: the only spellings that may grant. */
    static final String CONSENTS_PATH_MU = "µ_consentsµ";
    private static final String CONSENTS_NAME = "_consents";
    private static final char REPLACEMENT_CHARACTER = '\uFFFD';

    private static final Pattern STUDY_ID = Pattern.compile("phs[0-9]{6}");
    private static final Pattern PLAIN_NUMBER = Pattern.compile("[-+0-9.:]*");
    private static final char MU = 'µ';

    private final Path input;
    private final String runId;
    private final int maxOpenWriters;
    private final LongConsumer spoolWriteHook;
    private FailureSink failures;

    // Pass 1
    private final Map<String, Consent> consentsByValue = new HashMap<>();
    private final Map<Integer, ConsentSet> consentsByPatient = new HashMap<>();
    private final Map<ConsentSet, ConsentSet> internedSets = new HashMap<>();
    private final Set<String> studies = new HashSet<>();
    /** {@link #foldStudy} of every entry of {@link #studies}: a node that is no exact study but folds to one of these is unroutable. */
    private final Set<String> foldedStudies = new HashSet<>();
    private final Set<String> studiesOfRejectedRows = new TreeSet<>();
    private final List<Long> unparsableConsentLines = new ArrayList<>();
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
    private long consentsSubtreeRowsNotConsents;
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
        Map<String, Long> consentRowsRejectedByReason, long spoolWriterEvictions, int maxOpenWriters,
        List<String> studiesOnlyInRejectedConsentRows, long consentsSubtreeRowsNotConsents
    ) {
        /** Every row read is routed, dropped, rejected for its consents, a rejected consents row, or unroutable. */
        boolean balanced() {
            return rowsRead == rowsRouted + noConsentRowsDropped + invalidConsentRows + unroutableRows + consentRowsRejected;
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
        this(input, runId, maxOpenWriters, null);
    }

    /**
     * @param spoolWriteHook test seam, may be null: called with the number of spool lines written so far; may throw
     *        {@link UncheckedIOException} to simulate a full disk
     */
    ConsentRouter(Path input, String runId, int maxOpenWriters, LongConsumer spoolWriteHook) {
        if (maxOpenWriters < 1) {
            throw new IllegalArgumentException("maxOpenWriters must be at least 1, got " + maxOpenWriters);
        }
        this.input = input;
        this.runId = runId;
        this.maxOpenWriters = maxOpenWriters;
        this.spoolWriteHook = spoolWriteHook;
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
                        if (unparsableConsentLines.size() < MAX_LISTED_LINES) {
                            unparsableConsentLines.add(record.line());
                        }
                    }
                    continue;
                }
                PathForm form = pathForm(record.fields());
                if (form == PathForm.NOT_CONSENTS) {
                    ConsentRowCheck.Problem shape = consentsLikeShapeProblem(record.fields());
                    if (shape != null) { // might be a consents row that breaks R1 or R2: grants nothing, but its study is known
                        pass1Rejected.computeIfAbsent(shape, k -> new long[1])[0]++;
                        registerStudyOfRejectedRow(record.fields());
                    }
                    continue;
                }
                ConsentRowCheck.Problem problem = judge(form, record.fields());
                if (problem != null) {
                    pass1Rejected.computeIfAbsent(problem, k -> new long[1])[0]++;
                    registerStudyOfRejectedRow(record.fields());
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

    /** How a record relates to the consents variable. */
    enum PathForm {
        NOT_CONSENTS,
        /** Written exactly {@code \_consents\} or {@code µ_consentsµ}: may grant when the rest of the row passes. */
        CANONICAL,
        /** Reads as the consents variable in the partition's loader but is written otherwise: grants nothing. */
        NON_CANONICAL
    }

    /**
     * Whether the record is a row of the consents variable <b>as the partition's loader reads it</b> (a rejected one must never slip
     * through as a global row): the path after {@link CsvObservationProducer#normalizeConceptPath}, and after the loader's rule that drops
     * a last segment equal to the (trimmed, U+FFFD-free) text value, is {@code \_consents\}. Both the path before and after that drop
     * count, so a row is never less consents-like than the loader says. Only the two canonical spellings are {@link PathForm#CANONICAL}.
     */
    static PathForm pathForm(List<String> fields) {
        if (fields == null || fields.size() < 2) {
            return PathForm.NOT_CONSENTS;
        }
        String path = fields.get(1);
        if (path.equals(CONSENTS_PATH) || path.equals(CONSENTS_PATH_MU)) {
            return PathForm.CANONICAL;
        }
        if (path.indexOf(CONSENTS_NAME) < 0 && path.indexOf(REPLACEMENT_CHARACTER) < 0) { // cheap exit: normalization only removes text
            return PathForm.NOT_CONSENTS;
        }
        String normalized = CsvObservationProducer.normalizeConceptPath(path);
        if (CONSENTS_PATH.equals(normalized)) {
            return PathForm.NON_CANONICAL;
        }
        String text = fields.size() > 3 ? fields.get(3).trim().replace(String.valueOf(REPLACEMENT_CHARACTER), "") : "";
        if (!text.isEmpty() && normalized.endsWith("\\" + text + "\\")) {
            String dropped = normalized.substring(0, normalized.lastIndexOf('\\', normalized.length() - 2) + 1);
            if (CONSENTS_PATH.equals(dropped)) {
                return PathForm.NON_CANONICAL;
            }
        }
        return PathForm.NOT_CONSENTS;
    }

    /**
     * A row whose path is no consents path as the loader reads it, but mentions {@code _consents} (also in other case, also with U+FFFD
     * inside) and breaks R1 (text before the first delimiter) or R2 (empty or normalization-changed study node): {@code _consents\},
     * {@code x\_consents\}, {@code \\_consents\}. It might be a consents row, so it is a rejected consents row, never a data row: it grants
     * nothing, registers the study of its text value and counts against {@code maxRejectedConsentRows}. Pass 1 and pass 2 both ask this of
     * the fields alone (before the field count and the patient are looked at), so they always agree. Null when the row is not one.
     */
    static ConsentRowCheck.Problem consentsLikeShapeProblem(List<String> fields) {
        if (fields == null || fields.size() < 2) {
            return null;
        }
        String path = fields.get(1);
        if (!mentionsConsents(path)) {
            return null;
        }
        if (!segment(path, 0).isEmpty()) {
            return ConsentRowCheck.Problem.MALFORMED_CONCEPT_PATH;
        }
        String node = segment(path, 1);
        if (node.isEmpty() || studyNodeChangesOnNormalization(path, node)) {
            return ConsentRowCheck.Problem.INVALID_STUDY_NODE;
        }
        return null;
    }

    /** Contains {@code _consents} in any case, also when U+FFFD is removed. */
    private static boolean mentionsConsents(String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        return lower.contains(CONSENTS_NAME)
            || (lower.indexOf(REPLACEMENT_CHARACTER) >= 0 && lower.replace("\uFFFD", "").contains(CONSENTS_NAME));
    }

    /** Why a row of the consents variable may not grant, or null when it may. */
    private static ConsentRowCheck.Problem judge(PathForm form, List<String> fields) {
        return form == PathForm.NON_CANONICAL ? ConsentRowCheck.Problem.NON_CANONICAL_PATH : ConsentRowCheck.check(fields);
    }

    /**
     * A rejected consents row grants nothing but names a study: data rows of that study must not be taken for global rows. The text value
     * is read trimmed (as the router reads a consent) and also as the loader reads it (U+FFFD removed); a row with only three fields offers
     * its last field, the one a missing field would have shifted.
     */
    private void registerStudyOfRejectedRow(List<String> fields) {
        List<String> values = new ArrayList<>();
        if (fields.size() >= ConsentRowCheck.FIELDS) {
            values.add(fields.get(3));
        } else {
            // a row with fewer fields than the contract has its columns shifted: any of its fields from the third on may be the text value
            for (int i = 2; i < fields.size(); i++) {
                if (!PLAIN_NUMBER.matcher(fields.get(i).trim()).matches()) {
                    values.add(fields.get(i));
                }
            }
        }
        for (String value : values) {
            for (String candidate : List.of(value.trim(), value.trim().replace(String.valueOf(REPLACEMENT_CHARACTER), ""))) {
                if (!candidate.isEmpty()) {
                    String study = studyOf(candidate);
                    if (!study.isEmpty()) {
                        registerStudy(study);
                        studiesOfRejectedRows.add(study);
                    }
                }
            }
        }
    }

    /** Registers a study by its exact spelling and by its {@link #foldStudy folded} key. Every consents value goes through here. */
    private void registerStudy(String study) {
        studies.add(study);
        foldedStudies.add(foldStudy(study));
    }

    /**
     * The key by which two spellings of a study are taken for the same one: U+FFFD, whitespace, control and format characters removed
     * anywhere (the partition's loader trims and drops U+FFFD in paths; a consent value is trimmed), the part before the first dot, lower
     * case ({@link Locale#ROOT}). {@code phs999999}, {@code PHS999999}, {@code phs999999<U+FFFD>} and {@code phs999 999} fold alike. Used
     * for the consents' studies and for the data rows' study nodes.
     */
    static String foldStudy(String text) {
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length();) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            if (cp == REPLACEMENT_CHARACTER) {
                continue;
            }
            int type = Character.getType(cp); // by code point: tag characters (U+E0001-E007F, Cf) are supplementary
            if (
                type == Character.CONTROL || type == Character.SPACE_SEPARATOR || type == Character.LINE_SEPARATOR
                    || type == Character.PARAGRAPH_SEPARATOR || type == Character.FORMAT
            ) {
                continue;
            }
            if (cp == '.') {
                break;
            }
            sb.appendCodePoint(cp);
        }
        return sb.toString().toLowerCase(Locale.ROOT);
    }

    /**
     * Valid consents whose studies are different spellings of one study ({@code PHS999999.c1} and {@code phs999999.c2}): their rows could
     * not be told apart from a global row by the study node, so the run is refused. Pairs of exact studies; call after
     * {@link #learnConsents()}.
     */
    List<String> studyFoldClashes() {
        Map<String, Set<String>> byFold = new TreeMap<>();
        for (Consent consent : consentsByValue.values()) {
            if (consent.valid()) {
                byFold.computeIfAbsent(foldStudy(consent.study()), k -> new TreeSet<>()).add(consent.study());
            }
        }
        List<String> clashes = new ArrayList<>();
        byFold.values().stream().filter(spellings -> spellings.size() > 1).forEach(spellings -> clashes.add(String.join(" / ", spellings)));
        return clashes;
    }

    /** Consents rows on lines that cannot be parsed, which could not be read at all: their study is unknown, so the run must not go on. */
    long unparsableConsentRows() {
        return pass1Rejected.getOrDefault(ConsentRowCheck.Problem.UNPARSABLE_LINE, new long[1])[0];
    }

    /** The first physical line numbers of the unparsable consents rows (at most 10). */
    List<Long> unparsableConsentLines() {
        return List.copyOf(unparsableConsentLines);
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
            registerStudy(study);
            return new Consent(v, study, problem);
        });
    }

    /** Studies that only rejected consents rows name: their data rows are dropped, never global. Call after {@link #learnConsents()}. */
    private List<String> studiesOnlyInRejectedConsentRows() {
        Set<String> valid = new HashSet<>();
        consentsByValue.values().forEach(c -> valid.add(c.study()));
        return studiesOfRejectedRows.stream().filter(s -> !valid.contains(s)).toList();
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
        PathForm form = pathForm(record.fields());
        if (form != PathForm.NOT_CONSENTS) {
            ConsentRowCheck.Problem problem = judge(form, record.fields());
            if (problem != null) {
                rejectConsentRow(record, problem);
                return;
            }
        } else if (consentsLikeShapeProblem(record.fields()) != null) {
            rejectConsentRow(record, consentsLikeShapeProblem(record.fields())); // as in pass 1, before any other check of the row
            return;
        } else if (isConsentsSubtreeRow(record.fields())) {
            consentsSubtreeRowsNotConsents++; // stays a global row: the partition directory is what authorizes, but it is made visible
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
        if (!segment(path, 0).isEmpty()) {
            // R1: the segment before the first delimiter is empty (the path starts with a backslash or a mu)
            unroutable(
                record, FailureReason.MALFORMED_CONCEPT_PATH, patient,
                "The concept path does not start with a delimiter (the text before the first one is '" + segment(path, 0)
                    + "'); the row is not routed (the loader would store a study in a place no reader looks at)"
            );
            return;
        }
        String node = segment(path, 1);
        if (node.isEmpty()) {
            // R2: the study node (first node after the leading delimiter) is not empty
            unroutable(
                record, FailureReason.INVALID_STUDY_NODE, patient,
                "The study node (first node of the concept path) is empty; the row is not routed (it would be read as a global row)"
            );
            return;
        }
        if (studyNodeChangesOnNormalization(path, node)) {
            unroutable(
                record, FailureReason.INVALID_STUDY_NODE, patient,
                "The study node '" + node + "' is '" + segment(CsvObservationProducer.normalizeConceptPath(path), 1)
                    + "' once the path is normalized; the row is not routed (it would be read as a global or as a study row)"
            );
            return;
        }
        if (!studies.contains(node) && foldedStudies.contains(foldStudy(node))) {
            // another spelling of a study some consents value names (case, blanks, U+FFFD): a study row by any reading except this one's,
            // so it is never a global row; the consents' exact study spellings are the only ones that route
            unroutable(
                record, FailureReason.INVALID_STUDY_NODE, patient,
                "The study node '" + node + "' is not a study of any consent as written but reads as one when folded ('" + foldStudy(node)
                    + "'); the row is not routed (it could be a row of that study taken for a global row)"
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

    /** A row under {@code \_consents\} (the node at depth 1 of the normalized path is {@code _consents}) that is not a consents row. */
    private static boolean isConsentsSubtreeRow(List<String> fields) {
        if (fields.size() < 2) {
            return false;
        }
        String path = fields.get(1);
        if (path.indexOf(CONSENTS_NAME) < 0 && path.indexOf(REPLACEMENT_CHARACTER) < 0) {
            return false;
        }
        return CONSENTS_NAME.equals(segment(CsvObservationProducer.normalizeConceptPath(path), 1));
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

    /** A line that cannot be parsed may be a consents row; fail closed on anything that mentions the consents variable, in any case. */
    private static boolean mightBeConsentsRow(String raw) {
        return raw.replace("\uFFFD", "").toLowerCase(Locale.ROOT).contains(CONSENTS_NAME);
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
                FailureReason.INVALID_STUDY_NODE, FailureReason.MALFORMED_CONCEPT_PATH
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
            maxOpenWriters, studiesOnlyInRejectedConsentRows(), consentsSubtreeRowsNotConsents
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
