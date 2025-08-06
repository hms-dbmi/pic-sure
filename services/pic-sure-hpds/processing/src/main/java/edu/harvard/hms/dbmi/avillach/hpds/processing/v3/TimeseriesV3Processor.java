package edu.harvard.hms.dbmi.avillach.hpds.processing.v3;

import edu.harvard.hms.dbmi.avillach.hpds.data.phenotype.KeyAndValue;
import edu.harvard.hms.dbmi.avillach.hpds.data.phenotype.PhenoCube;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.Query;
import edu.harvard.hms.dbmi.avillach.hpds.processing.timeseries.TimeSeriesConversionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.*;

/**
 * A class for exporting datapoints from HPDS; this will export each individual input data as a unique row, allowing multiple data points
 * (with time data) to be exported for a single patient/concept combination.
 * 
 * This returns data in no meaningful order; it is exported by field parameters. Concepts which are present multiple times in a query will
 * only be exported once.
 *
 * @author nchu
 *
 *         Note: This class was copied from {@link edu.harvard.hms.dbmi.avillach.hpds.processing.timeseries.TimeseriesProcessor} and updated
 *         to use new Query entity
 *
 */
@Component
public class TimeseriesV3Processor implements HpdsV3Processor {

    private static final Logger log = LoggerFactory.getLogger(TimeseriesV3Processor.class);

    private final QueryExecutor queryExecutor;
    private final TimeSeriesConversionService conversionService;

    private final PhenotypicObservationStore phenotypicObservationStore;

    private final int ID_BATCH_SIZE;

    @Autowired
    public TimeseriesV3Processor(
        QueryExecutor queryExecutor, TimeSeriesConversionService conversionService, PhenotypicObservationStore phenotypicObservationStore
    ) {
        this.queryExecutor = queryExecutor;
        this.conversionService = conversionService;
        this.phenotypicObservationStore = phenotypicObservationStore;
        // todo: handle these via spring annotations
        ID_BATCH_SIZE = Integer.parseInt(System.getProperty("ID_BATCH_SIZE", "0"));
    }

    /**
     * FOr this type of export, the header is always the same
     */
    @Override
    public String[] getHeaderRow(Query query) {
        return new String[] {"PATIENT_NUM", "CONCEPT_PATH", "NVAL_NUM", "TVAL_CHAR", "TIMESTAMP"};
    }

    @Override
    public void runQuery(Query query, AsyncResult result) {
        Set<Integer> idList = queryExecutor.getPatientSubsetForQuery(query);

        if (ID_BATCH_SIZE > 0) {
            try {
                exportTimeData(query, result, idList);
            } catch (IOException e) {
                log.error("Exception exporting time data", e);
            }
        } else {
            throw new RuntimeException("Data Export is not authorized for this system");
        }
    }

    private void exportTimeData(Query query, AsyncResult result, Set<Integer> idList) throws IOException {
        log.info("Starting export for time series data of query {} (HPDS ID {})", query.picsureId(), query.id());
        Set<String> exportedConceptPaths = new HashSet<>();
        Collection<String> pathList = queryExecutor.getAllConceptPaths(query);

        addDataForConcepts(pathList, exportedConceptPaths, idList, result);
    }

    private void addDataForConcepts(
        Collection<String> pathList, Set<String> exportedConceptPaths, Set<Integer> idList, AsyncResult result
    ) {
        for (String conceptPath : pathList) {
            // skip concepts we may already have encountered
            if (exportedConceptPaths.contains(conceptPath)) {
                continue;
            }
            ArrayList<String[]> dataEntries = new ArrayList<>();
            Optional<PhenoCube<?>> maybeCube = phenotypicObservationStore.getCube(conceptPath);
            if (maybeCube.isEmpty()) {
                log.warn("Attempting export of non-existant concept: {}", conceptPath);
                continue;
            }
            PhenoCube<?> cube = maybeCube.get();
            log.debug("Exporting " + conceptPath);
            List<?> valuesForKeys = cube.getValuesForKeys(idList);
            for (Object kvObj : valuesForKeys) {
                if (cube.isStringType()) {
                    KeyAndValue<String> keyAndValue = (KeyAndValue) kvObj;
                    // "PATIENT_NUM","CONCEPT_PATH","NVAL_NUM","TVAL_CHAR","TIMESTAMP"
                    String[] entryData = {keyAndValue.getKey().toString(), conceptPath, "", keyAndValue.getValue(),
                        conversionService.toISOString(keyAndValue.getTimestamp())};
                    dataEntries.add(entryData);
                } else { // numeric
                    KeyAndValue<Double> keyAndValue = (KeyAndValue) kvObj;
                    // "PATIENT_NUM","CONCEPT_PATH","NVAL_NUM","TVAL_CHAR","TIMESTAMP"
                    String[] entryData = {keyAndValue.getKey().toString(), conceptPath, keyAndValue.getValue().toString(), "",
                        conversionService.toISOString(keyAndValue.getTimestamp())};
                    dataEntries.add(entryData);
                }
                // batch exports so we don't take double memory (valuesForKeys + dataEntries could be a lot of data points)
                if (dataEntries.size() >= (ID_BATCH_SIZE > 0 ? 10 : ID_BATCH_SIZE)) {
                    result.appendResults(dataEntries);
                    dataEntries = new ArrayList<>();
                }
            }
            result.appendResults(dataEntries);
            exportedConceptPaths.add(conceptPath);
        }
    }
}
