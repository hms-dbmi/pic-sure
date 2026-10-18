package edu.harvard.hms.dbmi.avillach.hpds.etl.phenotype.picsure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import edu.harvard.hms.dbmi.avillach.hpds.crypto.Crypto;
import edu.harvard.hms.dbmi.avillach.hpds.etl.phenotype.picsure.CsvObservationProducer.FileResult;
import edu.harvard.hms.dbmi.avillach.hpds.etl.phenotype.picsure.writer.HPDSWriterAdapter;
import edu.harvard.hms.dbmi.avillach.hpds.etl.phenotype.picsure.writer.HPDSWriterAdapter.WriterStats;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Loads allConcepts CSV files into encrypted HPDS phenotype stores, one partition per file: a drop-in for {@code CSVLoader} that reads
 * unsorted input, keeps its row semantics (see {@link CsvObservationProducer}) and reports what it did.
 *
 * <p>Usage: {@code java [-Dpicsure.loader.*=...] -jar PICSURELoader-jar-with-dependencies.jar <base>}. See {@link LoaderConfig} for the
 * properties.
 *
 * <p>Contract: <ul> <li>every regular file under {@code <base>} whose name ends in {@code allConcepts.csv} is a partition input (a symbolic
 * link to a file is fine; links are not resolved, nothing is ever written next to the link target); the list is complete before anything is
 * written. The name must end in exactly {@code -allConcepts.csv}; the partition is the name without that suffix, it must match
 * {@code ^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$}, may not contain {@code ..}, and may not repeat another partition's name, also ignoring case.
 * Anything else is refused before any work;</li> <li>{@code <base>/encryption_key} is loaded as {@link Crypto#DEFAULT_KEY_NAME}. A missing,
 * unreadable or wrong-length (not 16, 24 or 32 bytes) key is fatal, unlike {@code CSVLoader}, which would carry on without one;</li> <li>a
 * partition is written to {@code <parent of the input file>/<partition>/} as {@code allObservationsStore.javabin},
 * {@code columnMeta.javabin} and {@code columnMeta.csv}, plus {@code failures.jsonl} and {@code concept_rejections.jsonl}; the stores
 * decrypt with the key and are read by {@code PartitionedPhenotypicObservationStore} unchanged;</li> <li>partitions run one after the
 * other. A partition that fails (an exception, a concept that was rejected or could not be finalized, rows that do not add up, no concept
 * at all) has its three store files deleted, and the run ends non-zero. With {@code failFast} (the default) the remaining partitions are
 * not run;</li> <li>{@code <base>/ingest-summary.json} is written last, also when the run fails.</li> </ul>
 *
 * <p><b>Combined input.</b> With {@code -Dpicsure.loader.input=<file>} the input is one combined allConcepts file (the input of the
 * production hpds-ingest, any file name) instead of the files under {@code <base>}; the output still goes under {@code <base>}.
 * {@code -Dpicsure.loader.partitionBy=consent} (the default for a combined input) routes the rows by consent first, with the rules of the
 * {@code split-allconcepts-by-consent} job (see {@link ConsentRouter}): pass 1 learns the consents of every patient, pass 2 writes one
 * spool CSV per consent under {@code <spoolDir>/.routed}, and each of them is then loaded as above, one adapter at a time and deleted after
 * its partition succeeded (kept after a failure). The partitions are {@code <base>/<consent>/}. {@code partitionBy=none} loads the file as
 * one flat store in {@code <base>} (hpds-ingest's layout), for parity checks only: HPDS refuses a flat store when it requires the
 * authorization filter. Rows that cannot be routed are recorded in {@code <base>/routing-failures.jsonl}. {@code partitionBy} is refused
 * without {@code input}; a base that holds a flat store is refused in consent mode, and one that holds partition stores in none mode.
 *
 * <p>Exit status: 0 every partition loaded; 1 a partition failed; 2 the input or configuration was refused before any work.
 */
public final class PICSURELoader {

    static {
        // etl's logback.xml reads this property; without it the root level is not valid
        if (System.getProperty("logback.log.level") == null) {
            System.setProperty("logback.log.level", "INFO");
        }
    }

    private static final Logger log = LoggerFactory.getLogger(PICSURELoader.class);

    static final String ALL_CONCEPTS_SUFFIX = "-allConcepts.csv";
    static final Pattern PARTITION_NAME = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$");
    static final String SPOOL_DIR_NAME = ".picsure-spool";
    /** Under the spool root; no partition name can start with a dot, so it cannot collide with a partition's spool. */
    static final String ROUTED_DIR_NAME = ".routed";
    static final String ROUTING_FAILURES_FILE = "routing-failures.jsonl";
    /** Name of the one store of {@code partitionBy=none}; it is only used for the report and the spool directory. */
    static final String FLAT_NAME = "_flat";
    static final String SUMMARY_FILE = "ingest-summary.json";
    static final List<String> STORE_FILES = List.of("allObservationsStore.javabin", "columnMeta.javabin", "columnMeta.csv");

    static final int EXIT_OK = 0;
    static final int EXIT_PARTITION_FAILED = 1;
    static final int EXIT_REFUSED = 2;

    private static final int MAX_LISTED_CONCEPTS = 100;

    private static final ObjectMapper MAPPER = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    /** The input or configuration breaks the contract; nothing was written. */
    static final class RefusedException extends Exception {
        RefusedException(String message) {
            super(message);
        }
    }

    /** @param routedRows the lines the router wrote for this partition, or null when the input is not a routed file */
    record PartitionPlan(String name, Path inputFile, Path outputDir, Long routedRows) {
        PartitionPlan(String name, Path inputFile, Path outputDir) {
            this(name, inputFile, outputDir, null);
        }
    }

    private PICSURELoader() {}

    public static void main(String[] args) {
        System.exit(run(args, System::getProperty));
    }

    /**
     * Runs the loader.
     *
     * @param properties looks up a {@code picsure.loader.*} property
     * @return the exit status
     */
    static int run(String[] args, Function<String, String> properties) {
        Instant startedAt = Instant.now();
        if (args.length != 1 || args[0].isBlank()) {
            System.err.println("usage: java -jar PICSURELoader-jar-with-dependencies.jar <base directory>");
            return EXIT_REFUSED;
        }
        Path base = Path.of(args[0]);
        LoaderConfig config;
        try {
            config = LoaderConfig.fromProperties(properties);
        } catch (IllegalArgumentException e) {
            System.err.println("PICSURELoader refused: " + e.getMessage());
            return EXIT_REFUSED;
        }
        String runId = config.runId() != null ? config.runId() : "picsure-" + startedAt.toEpochMilli();
        if (!Files.isDirectory(base)) {
            System.err.println("PICSURELoader refused: " + base + " is not a directory");
            return EXIT_REFUSED;
        }

        boolean consentMode = config.combinedInput() && LoaderConfig.PARTITION_BY_CONSENT.equals(config.effectivePartitionBy());
        Path spoolRoot = config.spoolDir() != null ? config.spoolDir() : base.resolve(SPOOL_DIR_NAME);
        Path routedDir = spoolRoot.resolve(ROUTED_DIR_NAME);
        List<PartitionPlan> plans;
        ConsentRouter router = null;
        try {
            if (!config.combinedInput() && config.partitionBy() != null) {
                throw new RefusedException(
                    LoaderConfig.PREFIX + "partitionBy is only valid with " + LoaderConfig.PREFIX + "input (a combined file)"
                );
            }
            loadKey(base.resolve("encryption_key"));
            if (!config.combinedInput()) {
                plans = plan(base, config);
            } else {
                checkCombinedInput(config.input());
                if (consentMode) {
                    router = new ConsentRouter(config.input(), runId);
                    plans = planConsents(base, config, router);
                } else {
                    plans = planFlat(base, config);
                }
            }
        } catch (RefusedException | IOException e) {
            log.error("PICSURELoader refused: {}", e.getMessage());
            System.err.println("PICSURELoader refused: " + e.getMessage());
            writeSummary(base, summary(runId, base, config, startedAt, "refused", e.getMessage(), List.of(), null));
            return EXIT_REFUSED;
        }

        log.info("PICSURELoader run {}: {} partition(s) under {}", runId, plans.size(), base);
        ConsentRouter.Counts routing = null;
        if (consentMode) {
            try {
                routing = routeRows(router, base, routedDir, runId);
            } catch (IOException | RuntimeException e) {
                log.error("Routing {} failed", config.input(), e);
                return finish(base, runId, config, startedAt, "failed", "routing failed: " + e, plans, null);
            }
            if (routing.unroutableCrashClassRows() > config.maxRejectedRows()) {
                deleteTree(routedDir);
                removeEmptySpool(config, spoolRoot, routedDir);
                return finish(
                    base, runId, config, startedAt, "failed",
                    routing.unroutableCrashClassRows() + " row(s) could not be routed that CSVLoader would have stopped on "
                        + routing.unroutableByReason() + ", more than the " + config.maxRejectedRows() + " allowed (-D"
                        + LoaderConfig.PREFIX + "maxRejectedRows); see " + ROUTING_FAILURES_FILE,
                    plans, routing
                );
            }
            Map<String, Long> lines = routing.rowsPerPartition();
            plans = plans.stream().filter(p -> lines.containsKey(p.name()))
                .map(
                    p -> new PartitionPlan(p.name(), routedDir.resolve(p.name() + ALL_CONCEPTS_SUFFIX), p.outputDir(), lines.get(p.name()))
                ).toList();
        }

        List<Map<String, Object>> reports = new ArrayList<>();
        boolean allOk = true;
        for (PartitionPlan plan : plans) {
            if (!allOk && config.failFast()) {
                reports.add(notRun(plan));
                continue;
            }
            Map<String, Object> report = runPartition(plan, config, runId, spoolRoot);
            reports.add(report);
            if (!"ok".equals(report.get("status"))) {
                allOk = false;
            }
        }
        removeEmptySpool(config, spoolRoot, consentMode ? routedDir : null);

        writeSummary(base, summary(runId, base, config, startedAt, allOk ? "ok" : "failed", null, reports, routing));
        if (allOk) {
            log.info("PICSURELoader run {} finished: all {} partition(s) loaded", runId, plans.size());
            return EXIT_OK;
        }
        log.error("PICSURELoader run {} finished with failed partitions; see {}", runId, base.resolve(SUMMARY_FILE));
        System.err.println("PICSURELoader: at least one partition failed; see " + base.resolve(SUMMARY_FILE));
        return EXIT_PARTITION_FAILED;
    }

    /** Removes the routed directory and the default spool root when they are empty; the files of a failed partition keep them. */
    private static void removeEmptySpool(LoaderConfig config, Path spoolRoot, Path routedDir) {
        try {
            if (routedDir != null) {
                Files.deleteIfExists(routedDir);
            }
            if (config.spoolDir() == null) {
                Files.deleteIfExists(spoolRoot);
            }
        } catch (IOException e) {
            log.debug("Spool {} not removed: {}", spoolRoot, e.getMessage());
        }
    }

    /** Ends a run that failed before any partition was loaded: every partition is reported as not run. */
    private static int finish(
        Path base, String runId, LoaderConfig config, Instant startedAt, String status, String error, List<PartitionPlan> plans,
        ConsentRouter.Counts routing
    ) {
        System.err.println("PICSURELoader: " + error);
        writeSummary(
            base, summary(runId, base, config, startedAt, status, error, plans.stream().map(PICSURELoader::notRun).toList(), routing)
        );
        return EXIT_PARTITION_FAILED;
    }

    // ---------------------------------------------------------------------------------------------------------------------------
    // Key and discovery
    // ---------------------------------------------------------------------------------------------------------------------------

    /** Loads the key as DEFAULT after checking it, so a bad key stops the run instead of failing at the first encryption. */
    static void loadKey(Path keyFile) throws RefusedException, IOException {
        if (!Files.isRegularFile(keyFile) || !Files.isReadable(keyFile)) {
            throw new RefusedException("the encryption key " + keyFile + " is missing or unreadable");
        }
        if (Files.size(keyFile) > 4096) {
            throw new RefusedException("the encryption key file " + keyFile + " is too large to be a key");
        }
        // The same reading as Crypto.loadKey: whole file, trimmed
        int length = Files.readString(keyFile, StandardCharsets.UTF_8).trim().getBytes(StandardCharsets.UTF_8).length;
        if (length != 16 && length != 24 && length != 32) {
            throw new RefusedException("the encryption key in " + keyFile + " must be 16, 24 or 32 bytes (found " + length + ")");
        }
        Crypto.loadKey(Crypto.DEFAULT_KEY_NAME, keyFile.toString());
        if (!Crypto.hasKey(Crypto.DEFAULT_KEY_NAME)) {
            throw new RefusedException("the encryption key " + keyFile + " could not be loaded");
        }
    }

    /** Partition name of an input file name. */
    static String partitionNameFor(String fileName) throws RefusedException {
        if (!fileName.endsWith(ALL_CONCEPTS_SUFFIX)) {
            throw new RefusedException("'" + fileName + "' ends in allConcepts.csv but not in '" + ALL_CONCEPTS_SUFFIX + "'");
        }
        String name = fileName.substring(0, fileName.length() - ALL_CONCEPTS_SUFFIX.length());
        if (!PARTITION_NAME.matcher(name).matches()) {
            throw new RefusedException("partition name '" + name + "' (from " + fileName + ") does not match " + PARTITION_NAME.pattern());
        }
        if (name.contains("..")) {
            throw new RefusedException("partition name '" + name + "' contains '..'");
        }
        return name;
    }

    /** Walks the base directory and decides every partition before anything is written. */
    static List<PartitionPlan> plan(Path base, LoaderConfig config) throws RefusedException, IOException {
        List<Path> files;
        try (Stream<Path> walk = Files.walk(base)) {
            // isRegularFile follows a symbolic link to a file; links to directories are not walked into
            files = walk.filter(Files::isRegularFile).filter(p -> p.getFileName().toString().endsWith("allConcepts.csv")).sorted().toList();
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
        if (files.isEmpty()) {
            throw new RefusedException("no file ending in allConcepts.csv under " + base);
        }

        Map<String, PartitionPlan> byLowerName = new LinkedHashMap<>();
        for (Path file : files) {
            String name = partitionNameFor(file.getFileName().toString());
            PartitionPlan plan = new PartitionPlan(name, file, file.getParent().resolve(name));
            PartitionPlan clash = byLowerName.putIfAbsent(name.toLowerCase(Locale.ROOT), plan);
            if (clash != null) {
                throw new RefusedException(
                    "partition '" + name + "' (" + file + ") repeats partition '" + clash.name() + "' (" + clash.inputFile()
                        + "), names are compared ignoring case"
                );
            }
            if (Files.exists(plan.outputDir()) && !Files.isDirectory(plan.outputDir())) {
                throw new RefusedException("the output path " + plan.outputDir() + " exists and is not a directory");
            }
            if (!config.overwrite()) {
                for (String stale : STORE_FILES) {
                    if (Files.exists(plan.outputDir().resolve(stale))) {
                        throw new RefusedException(
                            "partition " + name + " already has " + stale + " in " + plan.outputDir() + " (set -D" + LoaderConfig.PREFIX
                                + "overwrite=true to replace it)"
                        );
                    }
                }
            }
        }
        return byLowerName.values().stream().sorted(Comparator.comparing(PartitionPlan::name)).toList();
    }

    /** The combined input must be a readable file; it may sit anywhere, also outside the base. */
    static void checkCombinedInput(Path input) throws RefusedException {
        if (!Files.isRegularFile(input) || !Files.isReadable(input)) {
            throw new RefusedException("the input " + input + " (-D" + LoaderConfig.PREFIX + "input) is not a readable file");
        }
    }

    /** A store directly in the base directory: the layout of hpds-ingest, which HPDS refuses when it requires the authorization filter. */
    private static boolean hasRootStore(Path base) {
        return STORE_FILES.stream().anyMatch(f -> Files.exists(base.resolve(f)));
    }

    /** Subdirectories of the base that hold a store: partitions, as HPDS reads them. */
    private static List<String> partitionStores(Path base) throws IOException {
        try (Stream<Path> children = Files.list(base)) {
            return children.filter(Files::isDirectory).filter(d -> STORE_FILES.stream().anyMatch(f -> Files.exists(d.resolve(f))))
                .map(d -> d.getFileName().toString()).sorted().toList();
        }
    }

    /**
     * Combined input, {@code partitionBy=consent}: learns the consents (pass 1) and decides the partitions before anything is written. The
     * partitions are {@code <base>/<consent>/}; a flat store in the base is refused.
     */
    static List<PartitionPlan> planConsents(Path base, LoaderConfig config, ConsentRouter router) throws RefusedException, IOException {
        if (hasRootStore(base)) {
            throw new RefusedException(
                "the base " + base + " holds a flat store (" + String.join(", ", STORE_FILES)
                    + "); partitions would sit next to it and HPDS refuses such a directory. Use a fresh base directory"
            );
        }
        router.learnConsents();
        List<String> names = router.partitionNames();
        if (names.isEmpty()) {
            throw new RefusedException("no valid consent found under " + ConsentRouter.CONSENTS_PATH + " in " + config.input());
        }
        List<String> clashes = ConsentRouter.caseClashes(names);
        if (!clashes.isEmpty()) {
            throw new RefusedException("consents that differ only by case would share a partition directory: " + clashes);
        }
        List<PartitionPlan> plans = new ArrayList<>();
        for (String name : names) {
            PartitionPlan plan = new PartitionPlan(name, null, base.resolve(name));
            checkOutputDir(plan, config);
            plans.add(plan);
        }
        return plans;
    }

    /** Combined input, {@code partitionBy=none}: one flat store in the base, for parity checks. */
    static List<PartitionPlan> planFlat(Path base, LoaderConfig config) throws RefusedException, IOException {
        List<String> stores = partitionStores(base);
        if (!stores.isEmpty()) {
            throw new RefusedException(
                "the base " + base + " already holds partition stores " + stores + "; a flat store next to them would mix two layouts"
            );
        }
        PartitionPlan plan = new PartitionPlan(FLAT_NAME, config.input(), base);
        checkOutputDir(plan, config);
        return List.of(plan);
    }

    private static void checkOutputDir(PartitionPlan plan, LoaderConfig config) throws RefusedException {
        if (Files.exists(plan.outputDir()) && !Files.isDirectory(plan.outputDir())) {
            throw new RefusedException("the output path " + plan.outputDir() + " exists and is not a directory");
        }
        if (!config.overwrite()) {
            for (String stale : STORE_FILES) {
                if (Files.exists(plan.outputDir().resolve(stale))) {
                    throw new RefusedException(
                        "partition " + plan.name() + " already has " + stale + " in " + plan.outputDir() + " (set -D" + LoaderConfig.PREFIX
                            + "overwrite=true to replace it)"
                    );
                }
            }
        }
    }

    /** Pass 2: writes the routed files under {@code routedDir} (leftovers of an earlier run are removed first). */
    private static ConsentRouter.Counts routeRows(ConsentRouter router, Path base, Path routedDir, String runId) throws IOException {
        deleteTree(routedDir);
        try (FailureSink sink = new FailureSink(base.resolve(ROUTING_FAILURES_FILE))) {
            return router.route(routedDir, sink);
        }
    }

    // ---------------------------------------------------------------------------------------------------------------------------
    // One partition
    // ---------------------------------------------------------------------------------------------------------------------------

    private static Map<String, Object> notRun(PartitionPlan plan) {
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("name", plan.name());
        if (plan.inputFile() != null) {
            report.put("inputFile", plan.inputFile().toString());
        }
        report.put("outputDir", plan.outputDir().toString());
        report.put("status", "not_run");
        return report;
    }

    static Map<String, Object> runPartition(PartitionPlan plan, LoaderConfig config, String runId, Path spoolRoot) {
        long startNanos = System.nanoTime();
        Map<String, Object> report = notRun(plan);
        String error = null;
        Path spool = spoolRoot.resolve(plan.name());
        log.info("Partition {}: loading {} into {}", plan.name(), plan.inputFile(), plan.outputDir());

        HPDSWriterAdapter adapter = null;
        FailureSink failureSink = null;
        try {
            Files.createDirectories(plan.outputDir());
            // A store from an earlier run must not survive a failed overwrite
            deleteStoreFiles(plan.outputDir());
            failureSink = new FailureSink(plan.outputDir().resolve("failures.jsonl"));
            adapter = new HPDSWriterAdapter(
                spool, plan.outputDir(), Crypto.DEFAULT_KEY_NAME, config.storeCacheSize(), config.maxObservationsPerConcept(),
                config.finalizationConcurrency(), config.finalizationChunkSize(), config.dedup()
            );
            CsvObservationProducer producer = new CsvObservationProducer(runId, failureSink, config.effectiveChunkConcurrency());

            long ingestStart = System.nanoTime();
            FileResult fileResult = producer.processFile(plan.inputFile(), adapter::acceptBatch, config.batchSize());
            long ingestMs = (System.nanoTime() - ingestStart) / 1_000_000;

            long finalizeStart = System.nanoTime();
            adapter.closeAndSave();
            long finalizeMs = (System.nanoTime() - finalizeStart) / 1_000_000;

            error = evaluate(report, config, plan.routedRows(), fileResult, adapter, ingestMs, finalizeMs);
        } catch (Throwable t) {
            log.error("Partition {} failed", plan.name(), t);
            error = t.getClass().getSimpleName() + ": " + t.getMessage();
        } finally {
            if (adapter != null) {
                adapter.close(); // discards the spool of a store that was not saved
            }
            if (failureSink != null) {
                try {
                    failureSink.close();
                } catch (IOException e) {
                    log.warn("Could not close the failure file of partition {}: {}", plan.name(), e.getMessage());
                }
            }
            deleteTree(spool);
        }

        if (error != null) {
            deleteStoreFiles(plan.outputDir());
            report.put("status", "failed");
            report.put("error", error);
            log.error("Partition {} FAILED: {}. Its store files were removed.", plan.name(), error);
        } else {
            report.put("status", "ok");
            if (plan.routedRows() != null) {
                deleteRoutedFile(plan.inputFile()); // a failed partition keeps its routed file for diagnosis
            }
            log.info(
                "Partition {} loaded: {} concepts, {} observations", plan.name(), report.get("concepts"), report.get("observationsWritten")
            );
        }
        Map<String, Object> timings = new LinkedHashMap<>(timings(report));
        timings.put("totalMs", (System.nanoTime() - startNanos) / 1_000_000);
        report.put("timingsMs", timings);
        return report;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> timings(Map<String, Object> report) {
        Object existing = report.get("timingsMs");
        return existing instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }

    /**
     * Fills the report from the finished store and decides whether the partition is complete.
     *
     * @return null if the partition succeeded, otherwise why it did not
     */
    private static String evaluate(
        Map<String, Object> report, LoaderConfig config, Long routedRows, FileResult file, HPDSWriterAdapter adapter, long ingestMs,
        long finalizeMs
    ) {
        WriterStats stats = adapter.getStats();
        Map<String, String> failedConcepts = adapter.getFailedConcepts();
        Map<String, Long> rejectedConcepts = adapter.getRejectedConcepts();
        long written = adapter.getObservationsWritten();
        int concepts = adapter.getConceptCount() - failedConcepts.size();

        Map<String, Long> rejected = new TreeMap<>();
        file.rejected().forEach((reason, count) -> rejected.put(reason.name(), count));
        Map<String, Long> warnings = new TreeMap<>();
        file.warnings().forEach((reason, count) -> warnings.put(reason.name(), count));

        report.put("rowsRead", file.recordsRead());
        report.put("rowsRejected", rejected);
        report.put("rowsKeptWithWarning", warnings);
        report.put("observationsProduced", file.observationsProduced());
        Map<String, Object> writer = new LinkedHashMap<>();
        writer.put("accepted", stats.accepted());
        writer.put("invalid", stats.invalid());
        writer.put("rejectedConceptLimit", stats.rejectedConceptLimit());
        writer.put("skippedNullSentinel", stats.skippedNullSentinel());
        writer.put("duplicatesDropped", stats.duplicatesDropped());
        report.put("writer", writer);
        report.put("observationsWritten", written);
        report.put("spooledPartials", adapter.getSpooledPartials());

        // Rows CSVLoader would have stopped on are errors, not data to lose quietly (its silent skips, fewer than 4 columns and rows
        // without a value, are not counted)
        Map<String, Long> crashClass = new TreeMap<>();
        long crashClassRows = 0;
        for (Map.Entry<FailureReason, Long> entry : file.rejected().entrySet()) {
            if (entry.getKey().crashesCsvLoader()) {
                crashClass.put(entry.getKey().name(), entry.getValue());
                crashClassRows += entry.getValue();
            }
        }
        long timestampFailures = file.warnings().getOrDefault(FailureReason.TIMESTAMP_PARSE_ERROR, 0L);
        Map<String, Object> tolerance = new LinkedHashMap<>();
        tolerance.put("rowsRejectedLikeCsvLoaderCrashes", crashClassRows);
        tolerance.put("byReason", crashClass);
        tolerance.put("maxRejectedRows", config.maxRejectedRows());
        tolerance.put("timestampFailures", timestampFailures);
        tolerance.put("maxTimestampFailures", config.maxTimestampFailures());
        report.put("dataQuality", tolerance);
        report.put("concepts", concepts);
        report.put("patients", adapter.getPatientCount());
        report.put("failedConceptCount", failedConcepts.size());
        report.put("failedConcepts", limit(failedConcepts));
        report.put("rejectedConceptCount", rejectedConcepts.size());
        report.put("rejectedConcepts", limit(rejectedConcepts));
        Map<String, Object> timings = new LinkedHashMap<>();
        timings.put("ingestMs", ingestMs);
        timings.put("finalizeMs", finalizeMs);
        report.put("timingsMs", timings);

        // Every row is accounted for: read = rejected + produced; produced = invalid + accepted + skipped + refused;
        // accepted = written + duplicates + rows of concepts that failed.
        boolean producerAdds = file.recordsRead() == file.rejectedTotal() + file.observationsProduced();
        boolean writerAdds =
            file.observationsProduced() == stats.invalid() + stats.accepted() + stats.skippedNullSentinel() + stats.rejectedConceptLimit();
        boolean storeAdds = stats.accepted() == written + stats.duplicatesDropped() + adapter.getFailedConceptObservations();
        report.put("reconciled", producerAdds && writerAdds && storeAdds);

        if (routedRows != null && routedRows != file.recordsRead()) {
            return "the routed file of the partition has " + routedRows + " row(s) but " + file.recordsRead() + " were read";
        }
        if (!rejectedConcepts.isEmpty()) {
            return rejectedConcepts.size()
                + " concept(s) reached the observation limit and lost observations (see concept_rejections.jsonl)";
        }
        if (!failedConcepts.isEmpty()) {
            return failedConcepts.size() + " concept(s) could not be written (see concept_rejections.jsonl); first: "
                + failedConcepts.entrySet().iterator().next();
        }
        if (crashClassRows > config.maxRejectedRows()) {
            return crashClassRows + " row(s) were dropped that CSVLoader would have stopped on " + crashClass + ", more than the "
                + config.maxRejectedRows() + " allowed (-D" + LoaderConfig.PREFIX + "maxRejectedRows); see failures.jsonl";
        }
        if (timestampFailures > config.maxTimestampFailures()) {
            return timestampFailures + " timestamp(s) could not be parsed, more than the " + config.maxTimestampFailures() + " allowed (-D"
                + LoaderConfig.PREFIX + "maxTimestampFailures); see failures.jsonl";
        }
        if (stats.invalid() > 0) {
            return stats.invalid() + " row(s) reached the writer without exactly one value";
        }
        if (!(producerAdds && writerAdds && storeAdds)) {
            return "the row counts do not add up (read " + file.recordsRead() + ", rejected " + file.rejectedTotal() + ", produced "
                + file.observationsProduced() + ", " + stats + ", written " + written + ")";
        }
        if (concepts < 1 || written < 1) {
            return "the partition holds no observation that could be stored (" + file.recordsRead() + " rows read)";
        }
        return null;
    }

    private static <V> Map<String, V> limit(Map<String, V> map) {
        Map<String, V> limited = new TreeMap<>();
        for (Map.Entry<String, V> entry : new TreeMap<>(map).entrySet()) {
            if (limited.size() >= MAX_LISTED_CONCEPTS) {
                break;
            }
            limited.put(entry.getKey(), entry.getValue());
        }
        return limited;
    }

    static void deleteStoreFiles(Path outputDir) {
        for (String name : STORE_FILES) {
            try {
                Files.deleteIfExists(outputDir.resolve(name));
            } catch (IOException e) {
                log.error("Could not delete {} from {}: {}", name, outputDir, e.getMessage());
            }
        }
    }

    private static void deleteRoutedFile(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            log.warn("Could not delete the routed file {}: {}", file, e.getMessage());
        }
    }

    private static void deleteTree(Path dir) {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        } catch (IOException | UncheckedIOException e) {
            log.warn("Could not remove the spool directory {}: {}", dir, e.getMessage());
        }
    }

    // ---------------------------------------------------------------------------------------------------------------------------
    // Summary
    // ---------------------------------------------------------------------------------------------------------------------------

    private static Map<String, Object> summary(
        String runId, Path base, LoaderConfig config, Instant startedAt, String status, String error, List<Map<String, Object>> partitions,
        ConsentRouter.Counts routing
    ) {
        Instant finishedAt = Instant.now();
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("schema", 1);
        summary.put("loader", "PICSURELoader");
        summary.put("runId", runId);
        summary.put("status", status);
        if (error != null) {
            summary.put("error", error);
        }
        summary.put("base", base.toString());
        summary.put("inputMode", config.combinedInput() ? "combined" : "split");
        summary.put("partitionBy", config.combinedInput() ? config.effectivePartitionBy() : null);
        summary.put("inputFile", config.combinedInput() ? config.input().toString() : null);
        summary.put("inputSizeBytes", config.combinedInput() ? sizeOrNull(config.input()) : null);
        if (routing != null) {
            summary.put("consentRulesMatch", ConsentRouter.RULES_MATCH);
            summary.put("routing", routingSummary(routing));
        }
        summary.put("startedAt", startedAt.toString());
        summary.put("finishedAt", finishedAt.toString());
        summary.put("durationMs", finishedAt.toEpochMilli() - startedAt.toEpochMilli());
        Map<String, Object> settings = new LinkedHashMap<>();
        settings.put("dedup", config.dedup());
        settings.put("maxObservationsPerConcept", config.maxObservationsPerConcept());
        settings.put("batchSize", config.batchSize());
        settings.put("storeCacheSize", config.storeCacheSize());
        settings.put("finalizationConcurrency", config.finalizationConcurrency());
        settings.put("failFast", config.failFast());
        settings.put("maxRejectedRows", config.maxRejectedRows());
        settings.put("maxTimestampFailures", config.maxTimestampFailures());
        settings.put("chunkConcurrency", config.effectiveChunkConcurrency());
        summary.put("settings", settings);
        summary.put("partitions", partitions);
        long rows = 0;
        long written = 0;
        long concepts = 0;
        for (Map<String, Object> p : partitions) {
            rows += p.get("rowsRead") instanceof Number n ? n.longValue() : 0;
            written += p.get("observationsWritten") instanceof Number n ? n.longValue() : 0;
            concepts += p.get("concepts") instanceof Number n ? n.longValue() : 0;
        }
        Map<String, Object> totals = new LinkedHashMap<>();
        totals.put("partitions", partitions.size());
        totals.put("partitionsOk", partitions.stream().filter(p -> "ok".equals(p.get("status"))).count());
        totals.put("rowsRead", rows);
        totals.put("observationsWritten", written);
        totals.put("concepts", concepts);
        summary.put("totals", totals);
        return summary;
    }

    private static Long sizeOrNull(Path file) {
        try {
            return Files.size(file);
        } catch (IOException e) {
            return null;
        }
    }

    private static Map<String, Object> routingSummary(ConsentRouter.Counts counts) {
        Map<String, Object> routing = new LinkedHashMap<>();
        routing.put("rowsRead", counts.rowsRead());
        routing.put("headerRowSkipped", counts.headerSkipped());
        routing.put("rowsRouted", counts.rowsRouted());
        routing.put("studyRows", counts.studyRows());
        routing.put("studyRowCopies", counts.studyRowCopies());
        routing.put("globalRows", counts.globalRows());
        routing.put("globalRowCopies", counts.globalRowCopies());
        routing.put("linesWritten", counts.linesWritten());
        routing.put("rowsPerPartition", counts.rowsPerPartition());
        routing.put("noConsentRowsDropped", counts.noConsentRowsDropped());
        routing.put("noConsentStudyRows", counts.noConsentStudyRows());
        routing.put("noConsentGlobalRows", counts.noConsentGlobalRows());
        routing.put("patientsWithConsents", counts.patientsWithConsents());
        routing.put("patientsWithoutConsent", counts.patientsWithoutConsent());
        routing.put("invalidConsentNamesDropped", counts.invalidConsentNames());
        routing.put("invalidConsentRowsRejected", counts.invalidConsentRows());
        routing.put("unroutableRows", counts.unroutableRows());
        routing.put("unroutableByReason", counts.unroutableByReason());
        routing.put("studyNodesWithoutConsents", counts.studyNodesWithoutConsents());
        routing.put("blankConsentRowsIgnored", counts.blankConsentRows());
        routing.put("balanced", counts.balanced());
        return routing;
    }

    /** Written through a temporary file so a reader never sees half a summary; a failure to write it is logged, not thrown. */
    private static void writeSummary(Path base, Map<String, Object> summary) {
        Path target = base.resolve(SUMMARY_FILE);
        Path tmp = base.resolve(SUMMARY_FILE + ".tmp");
        try {
            Files.writeString(
                tmp, MAPPER.writeValueAsString(summary) + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE
            );
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            log.error("Could not write {}: {}", target, e.getMessage());
        }
    }
}
