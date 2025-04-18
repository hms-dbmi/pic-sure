package edu.harvard.hms.dbmi.avillach.hpds.etl.phenotype.sequential;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ExecutionException;

import edu.harvard.hms.dbmi.avillach.hpds.etl.phenotype.config.CSVConfig;
import edu.harvard.hms.dbmi.avillach.hpds.etl.phenotype.config.ConfigLoader;
import edu.harvard.hms.dbmi.avillach.hpds.etl.phenotype.litecsv.LowRAMMultiCSVLoader;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVRecord;
import org.apache.commons.io.IOUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import com.google.common.cache.CacheLoader.InvalidCacheLoadException;

import edu.harvard.hms.dbmi.avillach.hpds.crypto.Crypto;
import edu.harvard.hms.dbmi.avillach.hpds.data.phenotype.PhenoCube;

/**
 * Generates an HPDS data store "/opt/local/hpds/allObservationsStore.javabin" with all phenotype concepts from the provided input files.
 * 
 * If no arguments are provided it will read a list of files from /opt/local/hpds/phenotypeInputs.txt, expecting one file per line.
 * 
 * @author nchu
 *
 */
@SuppressWarnings({"unchecked", "rawtypes"})
public class SequentialLoader {

    private static final String INPUT_DIR = "/opt/local/hpds_input/";

    private static final long LOG_INTERVAL = 1000000;

    private static SequentialLoadingStore store = new SequentialLoadingStore();

    private static Logger log = LoggerFactory.getLogger(SequentialLoader.class);

    private static JdbcTemplate template = null;

    private static long processedRecords = 0;

    private static final int PATIENT_NUM = 0;

    private static final int CONCEPT_PATH = 1;

    private static final int NUMERIC_VALUE = 2;

    private static final int TEXT_VALUE = 3;

    private static final int DATETIME = 4;

    private static String HPDS_DIRECTORY = "/opt/local/hpds/";

    public static void main(String[] args) throws IOException, ClassNotFoundException {

        Crypto.loadDefaultKey();

        List<String> inputFiles = new ArrayList<String>();
        // read in input files
        if (args.length > 0) {
            inputFiles.addAll(Arrays.stream(args).filter(arg -> !arg.equals("NO_ROLLUP") && !arg.contains("MAX_CHUNK_SIZE")).toList());
        } else {
            inputFiles.addAll(readFileList());
        }

        if (inputFiles.isEmpty()) {
            // check for prior default file locations
            File file = new File("/opt/local/hpds/loadQuery.sql");
            if (file.isFile()) {
                inputFiles.add("/opt/local/hpds/loadQuery.sql");
            } else {
                file = new File("/opt/local/hpds/allConcepts.csv");
                if (file.isFile()) {
                    inputFiles.add("/opt/local/hpds/allConcepts.csv");
                }
            }
        }

        log.info("Input files: {}", Arrays.deepToString(inputFiles.toArray()));

        // Remove the config.json file from the inputFiles list if it exists.
        // We are only expecting to load CSV and SQL files.
        inputFiles.removeIf(file -> file.contains("config.json"));

        if (inputFiles.stream().allMatch(f -> f.endsWith(".csv"))) {
            LowRAMMultiCSVLoader.main(args);
        } else {
            log.info("Non-CSV files detected. Using SequentialLoader.");
        }

        ConfigLoader configLoader = new ConfigLoader();
        // load each into the observation store
        for (String filename : inputFiles) {
            log.info("Loading file {}", filename);
            Path path = Paths.get(HPDS_DIRECTORY + filename);
            String fileName = path.getFileName().toString();

            CSVConfig csvConfig = configLoader.getConfigFor(fileName);
            if (filename.toLowerCase(Locale.ENGLISH).endsWith("sql")) {
                loadSqlFile(filename, csvConfig);
            } else if (filename.toLowerCase(Locale.ENGLISH).endsWith("csv")) {
                loadCsvFile(filename, csvConfig);
            }
        }

        // then complete, which will compact, sort, and write out the data in the final place
        log.info("found a total of {} entries", processedRecords);
        store.saveStore();
        store.dumpStats();
    }

    private static List<? extends String> readFileList() throws IOException {
        List<String> inputFiles = new ArrayList<String>();
        Files.list(new File(INPUT_DIR).toPath()).forEach(path -> {
            inputFiles.add(INPUT_DIR + path.getFileName().toString());
        });
        return inputFiles;
    }

    private static void loadCsvFile(String filename, CSVConfig csvConfig) throws IOException {
        Reader in = new FileReader(filename);
        BufferedReader reader = new BufferedReader(in, 1024 * 1024);
        Iterable<CSVRecord> records = CSVFormat.DEFAULT.withSkipHeaderRecord().withFirstRecordAsHeader().parse(reader);

        // currentConcept is used to persist data across function calls and identify when we hit a new concept
        final PhenoCube[] currentConcept = new PhenoCube[1];
        int count = 0;
        for (CSVRecord record : records) {
            count++;
            if (count % 5000 == 0) {
                log.info("Processed {} records for file {}", count, filename);
            }
            if (record.size() < 4) {
                log.warn("Record number {} had less records than we expected so we are skipping it.", record.getRecordNumber());
                continue;
            }
            processRecord(currentConcept, record, csvConfig);
        }
        reader.close();
        in.close();
    }

    private static void loadSqlFile(String filename, CSVConfig csvConfig) throws IOException {
        loadTemplate();
        String loadQuery = IOUtils.toString(new FileInputStream(filename), StandardCharsets.UTF_8);

        final PhenoCube[] currentConcept = new PhenoCube[1]; // used to identify new concepts
        template.query(loadQuery, result -> {
            processRecord(currentConcept, new PhenoRecord(result), csvConfig);
        });
    }


    private static void loadTemplate() throws FileNotFoundException, IOException {
        if (template == null) {
            Properties props = new Properties();
            props.load(new FileInputStream(INPUT_DIR + "sql.properties"));
            template = new JdbcTemplate(
                new DriverManagerDataSource(
                    props.getProperty("datasource.url"), props.getProperty("datasource.user"), props.getProperty("datasource.password")
                )
            );
        }
    }

    private static void processRecord(final PhenoCube[] currentConcept, CSVRecord record, CSVConfig csvConfig) {
        if (record.size() < 4) {
            log.info("Record number " + record.getRecordNumber() + " had less records than we expected so we are skipping it.");
            return;
        }

        try {
            String conceptPathFromRow = record.get(CONCEPT_PATH);
            String[] segments = conceptPathFromRow.split("\\\\");
            for (int x = 0; x < segments.length; x++) {
                segments[x] = segments[x].trim();
            }
            conceptPathFromRow = String.join("\\", segments) + "\\";
            conceptPathFromRow = conceptPathFromRow.replaceAll("\\ufffd", "");
            String textValueFromRow = record.get(TEXT_VALUE) == null ? null : record.get(TEXT_VALUE).trim();
            if (textValueFromRow != null) {
                textValueFromRow = textValueFromRow.replaceAll("\\ufffd", "");
            }
            String conceptPath =
                conceptPathFromRow.endsWith("\\" + textValueFromRow + "\\") ? conceptPathFromRow.replaceAll("\\\\[^\\\\]*\\\\$", "\\\\")
                    : conceptPathFromRow;
            // Check if the conceptPath should be prepended with dataset name
            if (csvConfig != null && csvConfig.getDataset_name_as_root_node()) {
                String datasetName = csvConfig.getDataset_name();
                if (!conceptPath.startsWith("\\")) {
                    conceptPath = "\\" + conceptPath;
                }

                conceptPath = "\\" + datasetName + conceptPath;
            }

            // This is not getDouble because we need to handle null values, not coerce them into 0s
            String numericValue = record.get(NUMERIC_VALUE);
            if ((numericValue == null || numericValue.isEmpty()) && textValueFromRow != null) {
                try {
                    numericValue = Double.parseDouble(textValueFromRow) + "";
                } catch (NumberFormatException e) {

                }
            }
            boolean isAlpha = (numericValue == null || numericValue.isEmpty());
            if (currentConcept[0] == null || !currentConcept[0].name.equals(conceptPath)) {
                try {
                    currentConcept[0] = store.loadingCache.get(conceptPath);
                } catch (InvalidCacheLoadException e) {
                    currentConcept[0] = new PhenoCube(conceptPath, isAlpha ? String.class : Double.class);
                    store.loadingCache.put(conceptPath, currentConcept[0]);
                }
            }
            String value = isAlpha ? record.get(TEXT_VALUE) : numericValue;

            if (
                value != null && !value.trim().isEmpty()
                    && ((isAlpha && currentConcept[0].vType == String.class) || (!isAlpha && currentConcept[0].vType == Double.class))
            ) {
                value = value.trim();
                currentConcept[0]
                    .setColumnWidth(isAlpha ? Math.max(currentConcept[0].getColumnWidth(), value.getBytes().length) : Double.BYTES);
                int patientId = Integer.parseInt(record.get(PATIENT_NUM));
                Date date = null;
                if (record.size() > 4 && record.get(DATETIME) != null && !record.get(DATETIME).isEmpty()) {
                    date = new Date(Long.parseLong(record.get(DATETIME)));
                }
                currentConcept[0].add(patientId, isAlpha ? value : Double.parseDouble(value), date);
                store.allIds.add(patientId);
            }
        } catch (ExecutionException e) {
            // todo: do we really want to ignore this?
            log.error("Error processing record", e);
        }
    }

    private static void processRecord(final PhenoCube[] currentConcept, PhenoRecord record, CSVConfig csvConfig) {
        if (record == null) {
            return;
        }

        try {
            String conceptPathFromRow = record.getConceptPath();
            String[] segments = conceptPathFromRow.split("\\\\");
            for (int x = 0; x < segments.length; x++) {
                segments[x] = segments[x].trim();
            }
            conceptPathFromRow = String.join("\\", segments) + "\\";
            conceptPathFromRow = conceptPathFromRow.replaceAll("\\ufffd", "");
            String textValueFromRow = record.getTextValue();
            if (textValueFromRow != null) {
                textValueFromRow = textValueFromRow.replaceAll("\\ufffd", "");
            }

            String conceptPath =
                conceptPathFromRow.endsWith("\\" + textValueFromRow + "\\") ? conceptPathFromRow.replaceAll("\\\\[^\\\\]*\\\\$", "\\\\")
                    : conceptPathFromRow;
            // Check if the conceptPath should be prepended with a dataset name
            if (csvConfig != null && csvConfig.getDataset_name_as_root_node()) {
                String datasetName = csvConfig.getDataset_name();
                if (!conceptPath.startsWith("\\")) {
                    conceptPath = "\\" + conceptPath;
                }

                conceptPath = "\\" + datasetName + conceptPath;
            }

            // This is not getDouble because we need to handle null values, not coerce them into 0s
            String numericValue = record.getNumericValue();
            if ((numericValue == null || numericValue.isEmpty()) && textValueFromRow != null) {
                try {
                    numericValue = Double.parseDouble(textValueFromRow) + "";
                } catch (NumberFormatException e) {

                }
            }
            boolean isAlpha = (numericValue == null || numericValue.isEmpty());
            if (currentConcept[0] == null || !currentConcept[0].name.equals(conceptPath)) {
                try {
                    currentConcept[0] = store.loadingCache.get(conceptPath);
                } catch (InvalidCacheLoadException e) {
                    log.debug("New concept " + record.getConceptPath());
                    currentConcept[0] = new PhenoCube(conceptPath, isAlpha ? String.class : Double.class);
                    store.loadingCache.put(conceptPath, currentConcept[0]);
                }
            }
            String value = isAlpha ? record.getTextValue() : numericValue;

            if (
                value != null && !value.trim().isEmpty()
                    && ((isAlpha && currentConcept[0].vType == String.class) || (!isAlpha && currentConcept[0].vType == Double.class))
            ) {
                value = value.trim();
                currentConcept[0]
                    .setColumnWidth(isAlpha ? Math.max(currentConcept[0].getColumnWidth(), value.getBytes().length) : Double.BYTES);
                int patientId = record.getPatientNum();

                currentConcept[0].add(patientId, isAlpha ? value : Double.parseDouble(value), record.getDateTime());
                store.allIds.add(patientId);
            }
            if (++processedRecords % LOG_INTERVAL == 0) {
                log.info("Loaded " + processedRecords + " records");
            }
        } catch (ExecutionException e) {
            // todo: do we really want to ignore this?
            log.error("Error processing record", e);
        }
    }
}
