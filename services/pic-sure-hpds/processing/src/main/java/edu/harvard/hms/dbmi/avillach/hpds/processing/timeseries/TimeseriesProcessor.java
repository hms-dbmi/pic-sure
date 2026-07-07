package edu.harvard.hms.dbmi.avillach.hpds.processing.timeseries;

import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

import edu.harvard.hms.dbmi.avillach.hpds.processing.AbstractProcessor;
import edu.harvard.hms.dbmi.avillach.hpds.processing.AsyncResult;
import edu.harvard.hms.dbmi.avillach.hpds.processing.HpdsProcessor;
import edu.harvard.hms.dbmi.avillach.hpds.processing.QueryProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import edu.harvard.hms.dbmi.avillach.hpds.data.phenotype.KeyAndValue;
import edu.harvard.hms.dbmi.avillach.hpds.data.phenotype.PhenoCube;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.Query;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * A class for exporting datapoints from HPDS; this will export each individual input data as a unique row, allowing multiple data points
 * (with time data) to be exported for a single patient/concept combination.
 * 
 * This returns data in no meaningful order; it is exported by field parameters. Concepts which are present multiple times in a query will
 * only be exported once.
 * 
 * 
 * 
 * @author nchu
 *
 */
@Component
public class TimeseriesProcessor implements HpdsProcessor {

    private static final Logger log = LoggerFactory.getLogger(QueryProcessor.class);

    private AbstractProcessor abstractProcessor;
    private final TimeSeriesConversionService conversionService;


    private final int idBatchSize;

    @Autowired
    public TimeseriesProcessor(
        AbstractProcessor abstractProcessor, TimeSeriesConversionService conversionService, @Value("${ID_BATCH_SIZE:0}") int idBatchSize
    ) {
        this.abstractProcessor = abstractProcessor;
        this.conversionService = conversionService;
        this.idBatchSize = idBatchSize;
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
        Set<Integer> idList = abstractProcessor.getPatientSubsetForQuery(query);

        if (idBatchSize > 0) {
            try {
                exportTimeData(query, result, idList);
            } catch (IOException e) {
                log.error("Exception exporting time data", e);
            }
        } else {
            throw new RuntimeException("Data Export is not authorized for this system");
        }
        return;
    }

    /**
     * //no variant data exported in this processor
     * 
     * @param query
     * @param result
     * @param idList
     * @throws IOException
     */
    private void exportTimeData(Query query, AsyncResult result, Set<Integer> idList) throws IOException {
        log.info("Starting export for time series data of query {} (HPDS ID {})", query.getPicSureId(), query.getId());
        Set<String> exportedConceptPaths = new HashSet<String>();
        // get a list of all fields mentioned in the query; export all data associated with any included field
        List<String> pathList = new LinkedList<String>();
        pathList.addAll(query.getAnyRecordOf());
        pathList.addAll(query.getAnyRecordOfMulti().stream().flatMap(Collection::stream).collect(Collectors.toList()));
        pathList.addAll(query.getFields());
        pathList.addAll(query.getRequiredFields());
        pathList.addAll(query.getCategoryFilters().keySet());
        pathList.addAll(query.getNumericFilters().keySet());

        addDataForConcepts(pathList, exportedConceptPaths, idList, result);
    }

    private void addDataForConcepts(Collection<String> pathList, Set<String> exportedConceptPaths, Set<Integer> idList, AsyncResult result)
        throws IOException {
        for (String conceptPath : pathList) {
            // skip concepts we may already have encountered
            if (exportedConceptPaths.contains(conceptPath)) {
                continue;
            }
            ArrayList<String[]> dataEntries = new ArrayList<String[]>();
            Optional<PhenoCube<?>> maybeCube = abstractProcessor.nullableGetCube(conceptPath);
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
                if (dataEntries.size() >= (idBatchSize > 0 ? 10 : idBatchSize)) {
                    result.appendResults(dataEntries);
                    dataEntries = new ArrayList<String[]>();
                }
            }
            result.appendResults(dataEntries);
            exportedConceptPaths.add(conceptPath);
        }
    }
}
