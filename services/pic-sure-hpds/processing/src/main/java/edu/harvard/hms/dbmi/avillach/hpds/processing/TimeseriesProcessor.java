package edu.harvard.hms.dbmi.avillach.hpds.processing;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.*;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import edu.harvard.dbmi.avillach.util.exception.NotAuthorizedException;
import edu.harvard.hms.dbmi.avillach.hpds.data.phenotype.KeyAndValue;
import edu.harvard.hms.dbmi.avillach.hpds.data.phenotype.PhenoCube;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.Query;
import edu.harvard.hms.dbmi.avillach.hpds.exception.NotEnoughMemoryException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * A class for exporting datapoints from HPDS; this will export each individual
 * input data as a unique row, allowing multiple data points (with time data) to
 * be exported for a single patient/concept combination.
 * 
 * This returns data in no meaningful order; it is exported by field parameters.
 * Concepts which are present multiple times in a query will only be exported
 * once.
 * 
 * 
 * 
 * @author nchu
 *
 */
@Component
public class TimeseriesProcessor implements HpdsProcessor {

	private Logger log = LoggerFactory.getLogger(QueryProcessor.class);

	private AbstractProcessor abstractProcessor;

	private final String ID_CUBE_NAME;
	private final int ID_BATCH_SIZE;
	private final int CACHE_SIZE;

	@Autowired
	public TimeseriesProcessor(AbstractProcessor abstractProcessor) {
		this.abstractProcessor = abstractProcessor;
		// todo: handle these via spring annotations
		CACHE_SIZE = Integer.parseInt(System.getProperty("CACHE_SIZE", "100"));
		ID_BATCH_SIZE = Integer.parseInt(System.getProperty("ID_BATCH_SIZE", "0"));
		ID_CUBE_NAME = System.getProperty("ID_CUBE_NAME", "NONE");
	}

	/**
	 * FOr this type of export, the header is always the same
	 */
	@Override
	public String[] getHeaderRow(Query query) {
		return  new String[] { "PATIENT_NUM", "CONCEPT_PATH", "NVAL_NUM", "TVAL_CHAR", "TIMESTAMP" };
	}

	@Override
	public void runQuery(Query query, AsyncResult result) {
		Set<Integer> idList = abstractProcessor.getPatientSubsetForQuery(query);

		if (ID_BATCH_SIZE > 0) {
			try {
				exportTimeData(query, result, idList);
			} catch (IOException e) {
				log.error("Exception exporting time data", e);
			}
		} else {
			// todo: create an exception for this and handle it with an appropriate response code
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

		Set<String> exportedConceptPaths = new HashSet<String>();
		//get a list of all fields mentioned in the query;  export all data associated with any included field
		List<String> pathList = new LinkedList<String>();
		pathList.addAll(query.getAnyRecordOf());
		pathList.addAll(query.getFields());
		pathList.addAll(query.getRequiredFields());
		pathList.addAll(query.getCategoryFilters().keySet());
		pathList.addAll(query.getNumericFilters().keySet());
		
		addDataForConcepts(pathList, exportedConceptPaths, idList, result);
	}

	private void addDataForConcepts(Collection<String> pathList, Set<String> exportedConceptPaths, Set<Integer> idList, AsyncResult result) throws IOException {
		for (String conceptPath : pathList) {
			//skip concepts we may already have encountered
			if(exportedConceptPaths.contains(conceptPath)) {
				continue;
			}
			ArrayList<String[]> dataEntries = new ArrayList<String[]>();
			PhenoCube<?> cube = abstractProcessor.getCube(conceptPath);
			if(cube == null) {
				log.warn("Attempting export of non-existant concept: " + conceptPath);
				continue;
			}
			log.debug("Exporting " + conceptPath);
			List<?> valuesForKeys = cube.getValuesForKeys(idList);
			for (Object kvObj : valuesForKeys) {
				if (cube.isStringType()) {
					KeyAndValue<String> keyAndValue = (KeyAndValue) kvObj;
					// "PATIENT_NUM","CONCEPT_PATH","NVAL_NUM","TVAL_CHAR","TIMESTAMP"
					String[] entryData = { keyAndValue.getKey().toString(), conceptPath, "", keyAndValue.getValue(),
							keyAndValue.getTimestamp().toString() };
					dataEntries.add(entryData);
				} else { // numeric
					KeyAndValue<Double> keyAndValue = (KeyAndValue) kvObj;
					// "PATIENT_NUM","CONCEPT_PATH","NVAL_NUM","TVAL_CHAR","TIMESTAMP"
					String[] entryData = { keyAndValue.getKey().toString(), conceptPath,
							keyAndValue.getValue().toString(), "", keyAndValue.getTimestamp().toString() };
					dataEntries.add(entryData);
				}
				//batch exports so we don't take double memory (valuesForKeys + dataEntries could be a lot of data points)
				if(dataEntries.size() >= ID_BATCH_SIZE) {
					result.stream.appendResults(dataEntries);
					dataEntries = new ArrayList<String[]>();
				}
			}
			result.stream.appendResults(dataEntries);
			exportedConceptPaths.add(conceptPath);
		}
	}
}
