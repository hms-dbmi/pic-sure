package edu.harvard.hms.dbmi.avillach.hpds.etl.phenotype;

import edu.harvard.hms.dbmi.avillach.hpds.crypto.Crypto;
import edu.harvard.hms.dbmi.avillach.hpds.data.phenotype.PhenoCube;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.util.Date;

@SuppressWarnings({"unchecked", "rawtypes"})
public class CSVLoaderNewSearch {

    private static final LoadingStore store = new LoadingStore();

    private static final Logger log = LoggerFactory.getLogger(CSVLoaderNewSearch.class);

    private static final int PATIENT_NUM = 0;

    private static final int CONCEPT_PATH = 1;

    private static final int NUMERIC_VALUE = 2;

    private static final int TEXT_VALUE = 3;

    private static final int DATETIME = 4;

    private static boolean DO_VARNAME_ROLLUP = false;

    private static final String HPDS_DIRECTORY = "/opt/local/hpds/";

    public static void main(String[] args) throws IOException {
        if (args.length > 1) {
            if (args[0].equalsIgnoreCase("NO_ROLLUP")) {
                log.info("NO_ROLLUP SET.");
                DO_VARNAME_ROLLUP = false;
            }
        }
        store.allObservationsStore = new RandomAccessFile(HPDS_DIRECTORY + "allObservationsStore.javabin", "rw");
        initialLoad();
        store.saveStore(HPDS_DIRECTORY);
    }

    private static void initialLoad() throws IOException {
        Crypto.loadDefaultKey();
        Reader in = new FileReader(HPDS_DIRECTORY + "allConcepts.csv");
        Iterable<CSVRecord> records = CSVFormat.DEFAULT.withSkipHeaderRecord().withFirstRecordAsHeader().parse(new BufferedReader(in, 1024 * 1024));

        final PhenoCube[] currentConcept = new PhenoCube[1];
        for (CSVRecord record : records) {
            processRecord(currentConcept, record);
        }
    }

    private static void processRecord(final PhenoCube[] currentConcept, CSVRecord record) {
        if (record.size() < 4) {
            log.info("Record number {} had less records than we expected so we are skipping it.", record.getRecordNumber());
            return;
        }

        String conceptPath = getSanitizedConceptPath(record);
        String numericValue = record.get(NUMERIC_VALUE);
        boolean isAlpha = (numericValue == null || numericValue.isEmpty());
        String value = isAlpha ? record.get(TEXT_VALUE) : numericValue;
        currentConcept[0] = getPhenoCube(currentConcept[0], conceptPath, isAlpha);

        if (value != null && !value.trim().isEmpty() &&
            ((isAlpha && currentConcept[0].vType == String.class) || (!isAlpha && currentConcept[0].vType == Double.class))) {
            value = value.trim();
            currentConcept[0].setColumnWidth(isAlpha ? Math.max(currentConcept[0].getColumnWidth(), value.getBytes().length) : Double.BYTES);
            int patientId = Integer.parseInt(record.get(PATIENT_NUM));
            Date date = null;
            if (record.size() > 4 && record.get(DATETIME) != null && !record.get(DATETIME).isEmpty()) {
                date = new Date(Long.parseLong(record.get(DATETIME)));
            }
            currentConcept[0].add(patientId, isAlpha ? value : Double.parseDouble(value), date);
            store.allIds.add(patientId);
        }
    }

    private static PhenoCube getPhenoCube(PhenoCube currentConcept, String conceptPath, boolean isAlpha) {
        if (currentConcept == null || !currentConcept.name.equals(conceptPath)) {
            currentConcept = store.store.getIfPresent(conceptPath);
            if (currentConcept == null) {
                currentConcept = new PhenoCube(conceptPath, isAlpha ? String.class : Double.class);
                store.store.put(conceptPath, currentConcept);
            }
        }

        return currentConcept;
    }

    private static String getSanitizedConceptPath(CSVRecord record) {
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
        String conceptPath;

        if (DO_VARNAME_ROLLUP) {
            conceptPath = conceptPathFromRow.endsWith("\\" + textValueFromRow + "\\") ? conceptPathFromRow.replaceAll("\\\\[^\\\\]*\\\\$", "\\\\") : conceptPathFromRow;
        } else {
            conceptPath = conceptPathFromRow;
        }
        return conceptPath;
    }


}
