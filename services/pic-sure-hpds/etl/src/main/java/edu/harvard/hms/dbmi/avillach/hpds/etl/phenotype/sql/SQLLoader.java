package edu.harvard.hms.dbmi.avillach.hpds.etl.phenotype.sql;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.text.SimpleDateFormat;
import java.io.*;
import java.nio.charset.Charset;
import java.util.Properties;
import java.util.concurrent.ExecutionException;

import com.google.common.cache.CacheLoader.InvalidCacheLoadException;

import edu.harvard.hms.dbmi.avillach.hpds.crypto.Crypto;
import edu.harvard.hms.dbmi.avillach.hpds.data.phenotype.PhenoCube;

import edu.harvard.hms.dbmi.avillach.hpds.etl.LoadingStore;
import org.apache.commons.io.IOUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

public class SQLLoader {

    private static final Logger log = LoggerFactory.getLogger(SQLLoader.class);

    private static final SimpleDateFormat ORACLE_DATE_FORMAT = new SimpleDateFormat("dd-MMM-yy");

    static JdbcTemplate template;

    private static LoadingStore store = new LoadingStore();

    private static final int PATIENT_NUM = 1;

    private static final int CONCEPT_PATH = 2;

    private static final int NUMERIC_VALUE = 3;

    private static final int TEXT_VALUE = 4;

    private static final int DATETIME = 5;

    private static final Properties props = new Properties();

    private static String HPDS_DIRECTORY = "/opt/local/hpds/";

    public static void main(String[] args) throws IOException {
        props.load(new FileInputStream(HPDS_DIRECTORY + "sql.properties"));
        template =
            new JdbcTemplate(new DriverManagerDataSource(prop("datasource.url"), prop("datasource.user"), prop("datasource.password")));
        store.allObservationsStore = new RandomAccessFile(HPDS_DIRECTORY + "allObservationsStore.javabin", "rw");
        Crypto.loadDefaultKey();
        initialLoad();
        store.saveStore(HPDS_DIRECTORY);
        store.dumpStats();
    }

    private static String prop(String key) {
        return props.getProperty(key);
    }

    private static void initialLoad() throws IOException {
        final PhenoCube[] currentConcept = new PhenoCube[1];
        String loadQuery = IOUtils.toString(new FileInputStream("/opt/local/hpds/loadQuery.sql"), Charset.forName("UTF-8"));
        //
        // ArrayBlockingQueue<String[]> abq = new ArrayBlockingQueue<>(1000);
        //
        // ExecutorService chunkWriteEx = Executors.newFixedThreadPool(32);
        //
        // boolean[] stillProcessingRecords = {true};
        //
        // for(int x = 0;x<32;x++) {
        // chunkWriteEx.submit(new Runnable() {
        // PhenoCube currentConcept = null;
        // String[] arg0;
        // @Override
        // public void run() {
        // while(stillProcessingRecords[0]) {
        // try {
        // arg0 = abq.poll(1, TimeUnit.SECONDS);
        // if(arg0 != null) {
        // String conceptPathFromRow = arg0[CONCEPT_PATH];
        // String[] segments = conceptPathFromRow.split("\\\\");
        // for(int x = 0;x<segments.length;x++) {
        // segments[x] = segments[x].trim();
        // }
        // conceptPathFromRow = String.join("\\", segments) + "\\";
        // conceptPathFromRow = conceptPathFromRow.replaceAll("\\ufffd", "");
        // String textValueFromRow = arg0[TEXT_VALUE] == null ? null : arg0[TEXT_VALUE].trim();
        // if(textValueFromRow!=null) {
        // textValueFromRow = textValueFromRow.replaceAll("\\ufffd", "");
        // }
        // String conceptPath = conceptPathFromRow.endsWith("\\" +textValueFromRow+"\\") ?
        // conceptPathFromRow.replaceAll("\\\\[^\\\\]*\\\\$", "\\\\") : conceptPathFromRow;
        // // This is not getDouble because we need to handle null values, not coerce them into 0s
        // String numericValue = arg0[NUMERIC_VALUE];
        // if((numericValue==null || numericValue.isEmpty()) && textValueFromRow!=null) {
        // try {
        // numericValue = Double.parseDouble(textValueFromRow) + "";
        // }catch(NumberFormatException e) {
        //
        // }
        // }
        // boolean isAlpha = (numericValue == null || numericValue.isEmpty());
        // if(currentConcept == null || !currentConcept.name.equals(conceptPath)) {
        // System.out.println(conceptPath);
        // try {
        // currentConcept = store.store.get(conceptPath);
        // } catch(InvalidCacheLoadException e) {
        // currentConcept = new PhenoCube(conceptPath, isAlpha ? String.class : Double.class);
        // store.store.put(conceptPath, currentConcept);
        // }
        // }
        // String value = isAlpha ? arg0[TEXT_VALUE] : numericValue;
        //
        // if(value != null && !value.trim().isEmpty() && ((isAlpha && currentConcept.vType == String.class)||(!isAlpha &&
        // currentConcept.vType == Double.class))) {
        // value = value.trim();
        // currentConcept.setColumnWidth(isAlpha ? Math.max(currentConcept.getColumnWidth(), value.getBytes().length) : Double.BYTES);
        // int patientId = Integer.parseInt(arg0[PATIENT_NUM]);
        // // DD-MMM-YY
        // currentConcept.add(patientId, isAlpha ? value : Double.parseDouble(value), arg0[DATETIME]==null? new Date(Long.MIN_VALUE) :
        // ORACLE_DATE_FORMAT.parse(arg0[DATETIME]));
        // store.allIds.add(patientId);
        // }
        // }
        // } catch (ExecutionException e) {
        // e.printStackTrace();
        // } catch (InterruptedException e1) {
        // // TODO Auto-generated catch block
        // e1.printStackTrace();
        // } catch (NumberFormatException e) {
        // // TODO Auto-generated catch block
        // e.printStackTrace();
        // } catch (ParseException e) {
        // // TODO Auto-generated catch block
        // e.printStackTrace();
        // }
        // }
        // }
        //
        // });
        // }

        template.query(loadQuery, new RowCallbackHandler() {

            @Override
            public void processRow(ResultSet arg0) throws SQLException {
                int row = arg0.getRow();
                if (row % 100000 == 0) {
                    System.out.println(row);
                }
                processRecord(currentConcept, arg0);
            }

        });

        try {
            Thread.sleep(5000);
        } catch (InterruptedException e) {
            log.error("Thread interrupted", e);
        }
        // stillProcessingRecords[0] = false;
        // chunkWriteEx.shutdown();
        // while(!chunkWriteEx.isTerminated()) {
        // try {
        // chunkWriteEx.awaitTermination(1, TimeUnit.SECONDS);
        // } catch (InterruptedException e) {
        // // TODO Auto-generated catch block
        // e.printStackTrace();
        // }
        // }
    }

    private static String lastConceptPath = "";
    private static String lastConceptPathCleaned = "";

    private static void processRecord(final PhenoCube[] currentConcept, ResultSet arg0) {
        try {
            String conceptPathFromRow = arg0.getString(CONCEPT_PATH);
            if (conceptPathFromRow.contentEquals(lastConceptPath)) {
                conceptPathFromRow = lastConceptPathCleaned;
            } else {
                String[] segments = conceptPathFromRow.split("\\\\");
                for (int x = 0; x < segments.length; x++) {
                    segments[x] = segments[x].trim();
                }
                conceptPathFromRow = String.join("\\", segments) + "\\";
                conceptPathFromRow = conceptPathFromRow.replaceAll("\\ufffd", "");
                lastConceptPath = conceptPathFromRow;
                lastConceptPathCleaned = conceptPathFromRow;
            }
            String textValueFromRow = arg0.getString(TEXT_VALUE) == null ? null : arg0.getString(TEXT_VALUE).trim();
            if (textValueFromRow != null) {
                textValueFromRow = textValueFromRow.replaceAll("\\ufffd", "");
            }
            String conceptPath =
                conceptPathFromRow.endsWith("\\" + textValueFromRow + "\\") ? conceptPathFromRow.replaceAll("\\\\[^\\\\]*\\\\$", "\\\\")
                    : conceptPathFromRow;
            // This is not getDouble because we need to handle null values, not coerce them into 0s
            String numericValue = arg0.getString(NUMERIC_VALUE);
            if ((numericValue == null || numericValue.isEmpty()) && textValueFromRow != null) {
                try {
                    numericValue = Double.parseDouble(textValueFromRow) + "";
                } catch (NumberFormatException e) {

                }
            }
            boolean isAlpha = (numericValue == null || numericValue.isEmpty());
            if (currentConcept[0] == null || !currentConcept[0].name.equals(conceptPath)) {
                System.out.println(conceptPath);
                try {
                    currentConcept[0] = store.store.get(conceptPath);
                } catch (InvalidCacheLoadException e) {
                    currentConcept[0] = new PhenoCube(conceptPath, isAlpha ? String.class : Double.class);
                    store.store.put(conceptPath, currentConcept[0]);
                }
            }
            String value = isAlpha ? arg0.getString(TEXT_VALUE) : numericValue;

            if (
                value != null && !value.trim().isEmpty()
                    && ((isAlpha && currentConcept[0].vType == String.class) || (!isAlpha && currentConcept[0].vType == Double.class))
            ) {
                value = value.trim();
                currentConcept[0]
                    .setColumnWidth(isAlpha ? Math.max(currentConcept[0].getColumnWidth(), value.getBytes().length) : Double.BYTES);
                int patientId = arg0.getInt(PATIENT_NUM);
                currentConcept[0].add(patientId, isAlpha ? value : Double.parseDouble(value), arg0.getDate(DATETIME));
                store.allIds.add(patientId);
            }
        } catch (ExecutionException | SQLException e) {
            // todo: do we really want to ignore these?
            log.error("Exception processing record", e);
        }
    }
}
