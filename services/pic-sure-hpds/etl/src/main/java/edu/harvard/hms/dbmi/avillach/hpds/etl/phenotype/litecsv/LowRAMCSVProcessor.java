package edu.harvard.hms.dbmi.avillach.hpds.etl.phenotype.litecsv;

import edu.harvard.hms.dbmi.avillach.hpds.data.phenotype.PhenoCube;
import edu.harvard.hms.dbmi.avillach.hpds.etl.phenotype.csv.CSVParserUtil;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.apache.logging.log4j.util.Strings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;
import java.io.*;
import java.nio.file.Files;
import java.util.*;
import java.util.stream.Stream;

public class LowRAMCSVProcessor {

    private static final Logger log = LoggerFactory.getLogger(LowRAMCSVProcessor.class);
    private final LowRAMLoadingStore store;
    private final boolean doVarNameRollup;
    private final double maxChunkSizeGigs;

    public LowRAMCSVProcessor(LowRAMLoadingStore store, boolean doVarNameRollup, double maxChunkSizeGigs) {
        this.store = store;
        this.doVarNameRollup = doVarNameRollup;
        this.maxChunkSizeGigs = maxChunkSizeGigs;
    }

    public IngestStatus process(File csv) {
        long startTime = System.nanoTime();
        log.info("Attempting to ingest file {}", csv.getAbsolutePath());
        try (Reader r = new FileReader(csv); Stream<String> rawLines = Files.lines(csv.toPath())) {
            CSVParser parser = CSVFormat.DEFAULT.withSkipHeaderRecord().withFirstRecordAsHeader().parse(new BufferedReader(r));

            // we want to read the file in reasonably sized chunks so that we can handle chunks naively
            // in memory without going OOM
            // to do this, we're going to assume that over the course of thousands of lines, each line
            // is more or less the same length
            // so we'll just provision n chunks, where n is max(1, file_size/max_chunk_size)
            log.info("Gathering stats about file {}", csv.getName());
            int chunks = Math.max(1, (int) Math.ceil((double) csv.length() / (maxChunkSizeGigs * 1024 * 1024 * 1024)));
            final long totalLineCount = rawLines.count();
            final long linesPerChunk = totalLineCount / chunks;
            log.info(
                "File {} is {} bytes and {} lines. Dividing into {} chunks of {} lines each", csv.getName(), csv.length(), totalLineCount,
                chunks, linesPerChunk
            );
            long chunkLineCount = 0;
            long lineCount = 0;
            int chunkCount = 0;
            Set<String> concepts = new HashSet<>();
            List<CSVRecord> lines = new ArrayList<>();

            log.info("Creating chunks");
            for (CSVRecord record : parser) {
                chunkLineCount++;
                lineCount++;
                lines.add(record);
                if (chunkLineCount > linesPerChunk || lineCount + 1 == totalLineCount) {
                    log.info("Finished creating chunk {}", chunkCount);
                    // sort by concept to prevent cache thrashing when ingesting
                    // loading each concept in its entirety for a chunk will minimize disk IO and
                    // let us keep more valuable things in RAM
                    lines.sort(Comparator.comparing(a -> a.get(1)));
                    log.info("Finished sorting chunk {}", chunkCount);
                    Set<String> chunkConcepts = ingest(lines);
                    concepts.addAll(chunkConcepts);
                    log.info("Finished ingesting chunk {} with {} unique concepts", chunkCount, chunkConcepts.size());
                    lines = new ArrayList<>();
                    chunkLineCount = 0;
                    chunkCount++;
                }
            }

            return new IngestStatus(csv.toPath(), totalLineCount, concepts.size(), System.nanoTime() - startTime);

        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }


    private Set<String> ingest(List<CSVRecord> sortedRecords) {
        Set<String> concepts = new HashSet<>();
        for (CSVRecord record : sortedRecords) {
            if (record.size() < 4) {
                log.warn("Record #{} has too few columns, skipping.", record.getRecordNumber());
                continue;
            }

            String conceptPath = CSVParserUtil.parseConceptPath(record, doVarNameRollup);
            // TODO: check logic
            IngestFn ingestFn = Strings.isEmpty(record.get(CSVParserUtil.NUMERIC_VALUE)) ? this::ingestNonNumeric : this::ingestNumeric;
            Date date = CSVParserUtil.parseDate(record);
            int patientId = Integer.parseInt(record.get(CSVParserUtil.PATIENT_NUM));
            if (ingestFn.attemptIngest(record, conceptPath, patientId, date)) {
                concepts.add(conceptPath);
            } else {
                log.warn("Could not ingest record #{}", record.getRecordNumber());
            }
        }
        return concepts;
    }

    @FunctionalInterface
    private interface IngestFn {
        boolean attemptIngest(CSVRecord record, String path, int patientId, @Nullable Date date);
    }

    private boolean ingestNumeric(CSVRecord record, String conceptPath, int patientId, Date date) {
        PhenoCube<Double> concept = store.loadingCache.getIfPresent(conceptPath);
        if (concept == null) {
            concept = new PhenoCube<>(conceptPath, Double.class);
            concept.setColumnWidth(Double.BYTES);
            store.loadingCache.put(conceptPath, concept);
        }
        if (!concept.vType.equals(Double.class)) {
            log.error("""
                Concept bucket {} was configured for non-numeric types, but received numeric value {}
                This happens when, for a single concept, there rows that have a tval_char, and other
                rows with an nval_num but no tval_char.
                Offending record #{}
                """, conceptPath, record.get(CSVParserUtil.NUMERIC_VALUE), record.getRecordNumber());
            return false;
        }
        try {
            String rawNumericValue = CSVParserUtil.trim(record.get(CSVParserUtil.NUMERIC_VALUE));
            double parsedValue = Double.parseDouble(rawNumericValue);
            concept.add(patientId, parsedValue, date);
            return true;
        } catch (NumberFormatException e) {
            log.warn("Could not parse numeric value in line {}", record);
        }
        return false;
    }

    private boolean ingestNonNumeric(CSVRecord record, String conceptPath, int patientId, Date date) {
        PhenoCube<String> concept = store.loadingCache.getIfPresent(conceptPath);
        if (concept == null) {
            concept = new PhenoCube<>(conceptPath, String.class);
            store.loadingCache.put(conceptPath, concept);
        }
        if (!concept.vType.equals(String.class)) {
            log.error("""
                Concept bucket {} was configured for numeric types, but received non-numeric value {}
                This happens when, for a single concept, there rows that have a tval_char, and other
                rows with an nval_num but no tval_char.
                Offending record #{}
                """, conceptPath, record.get(CSVParserUtil.TEXT_VALUE), record.getRecordNumber());
            return false;
        }
        String rawTextValue = CSVParserUtil.trim(record.get(CSVParserUtil.TEXT_VALUE));
        if (rawTextValue.isEmpty()) {
            return false;
        }
        concept.setColumnWidth(Math.max(rawTextValue.getBytes().length, concept.getColumnWidth()));
        concept.add(patientId, rawTextValue, date);
        return true;
    }
}
