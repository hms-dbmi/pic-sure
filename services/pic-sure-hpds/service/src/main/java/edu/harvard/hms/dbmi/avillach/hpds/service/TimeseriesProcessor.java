package edu.harvard.hms.dbmi.avillach.hpds.service;

import java.io.*;
import java.util.*;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import de.siegmar.fastcsv.writer.CsvWriter;
import edu.harvard.hms.dbmi.avillach.hpds.data.phenotype.KeyAndValue;
import edu.harvard.hms.dbmi.avillach.hpds.data.phenotype.PhenoCube;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.Query;
import edu.harvard.hms.dbmi.avillach.hpds.exception.NotEnoughMemoryException;
import edu.harvard.hms.dbmi.avillach.hpds.processing.AbstractProcessor;
import edu.harvard.hms.dbmi.avillach.hpds.processing.AsyncResult;
import edu.harvard.hms.dbmi.avillach.hpds.processing.QueryProcessor;

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
public class TimeseriesProcessor extends AbstractProcessor {

	private Logger log = LoggerFactory.getLogger(QueryProcessor.class);

	private static final String[] headers = { "PATIENT_NUM", "CONCEPT_PATH", "NVAL_NUM", "TVAL_CHAR", "TIMESTAMP" };

	public TimeseriesProcessor() throws ClassNotFoundException, FileNotFoundException, IOException {
		super();
	}

	@Override
	public void runQuery(Query query, AsyncResult result) throws NotEnoughMemoryException {
		TreeSet<Integer> idList = getPatientSubsetForQuery(query);
//		log.info("Processing " + idList.size() + " rows for result " + result.id);

		if (ID_BATCH_SIZE > 0) {
			try {
				exportTimeData(query, result, idList);
			} catch (IOException e) {
				e.printStackTrace();
			}
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
	private void exportTimeData(Query query, AsyncResult result, TreeSet<Integer> idList) throws IOException {

		List<String> exportedConceptPaths = new LinkedList<String>();

		File tempFile = File.createTempFile("result-" + System.nanoTime(), ".sstmp");
		CsvWriter writer = new CsvWriter();

		ArrayList<String[]> headerEntries = new ArrayList<String[]>();
		headerEntries.add(headers);
		try (FileWriter out = new FileWriter(tempFile);) {
			writer.write(out, headerEntries);

			//fields, requiredFields, and AnyRecordOf entries should all be added in the same way
			List<String> fieldList = new LinkedList<String>();
			fieldList.addAll(query.anyRecordOf);
			fieldList.addAll(query.fields);
			fieldList.addAll(query.requiredFields);
			
			for (String conceptPath : fieldList) {
				//skip concepts we may already have encountered
				if(exportedConceptPaths.contains(conceptPath)) {
					continue;
				}
				ArrayList<String[]> dataEntries = new ArrayList<String[]>();
				PhenoCube<?> cube = getCube(conceptPath);
				List<?> valuesForKeys = cube.getValuesForKeys(idList);
				if (cube.isStringType()) {
					for (Object kvObj : valuesForKeys) {
						KeyAndValue<String> keyAndValue = (KeyAndValue) kvObj;
						// "PATIENT_NUM","CONCEPT_PATH","NVAL_NUM","TVAL_CHAR","TIMESTAMP"
						String[] entryData = { keyAndValue.getKey().toString(), conceptPath, "", keyAndValue.getValue(),
								keyAndValue.getTimestamp().toString() };
						dataEntries.add(entryData);
					}
				} else { // numeric
					for (Object kvObj : valuesForKeys) {
						KeyAndValue<Double> keyAndValue = (KeyAndValue) kvObj;
						// "PATIENT_NUM","CONCEPT_PATH","NVAL_NUM","TVAL_CHAR","TIMESTAMP"
						String[] entryData = { keyAndValue.getKey().toString(), conceptPath,
								keyAndValue.getValue().toString(), "", keyAndValue.getTimestamp().toString() };
						dataEntries.add(entryData);
					}
				}
				writer.write(out, dataEntries);
				exportedConceptPaths.add(conceptPath);
			}
			
			for(String conceptPath : query.categoryFilters.keySet()) {
				//skip concepts we may already have encountered
				if(exportedConceptPaths.contains(conceptPath)) {
					continue;
				}
				
				ArrayList<String[]> dataEntries = new ArrayList<String[]>();
				PhenoCube<?> cube = getCube(conceptPath);
				List<?> valuesForKeys = cube.getValuesForKeys(idList);
				for (Object kvObj : valuesForKeys) {
					KeyAndValue<String> keyAndValue = (KeyAndValue) kvObj;
					
					/*
					 * Q: do we want to include all data for patients matching filter,
					 * even if that data point doesn't match?  e.g., Concept that changes from YES -> NO
					 * for a patient over time, do we include both values?
					 * if not, add a line to exclude unmatched values
					 * 
					 * String[] values = query.categoryFilters.get(conceptPath);
					 */
					
					// "PATIENT_NUM","CONCEPT_PATH","NVAL_NUM","TVAL_CHAR","TIMESTAMP"
					String[] entryData = { keyAndValue.getKey().toString(), conceptPath, "", keyAndValue.getValue(),
							keyAndValue.getTimestamp().toString() };
					dataEntries.add(entryData);
				}
				writer.write(out, dataEntries);
				exportedConceptPaths.add(conceptPath);
			}
			
			for(String conceptPath : query.numericFilters.keySet()) {
				//skip concepts we may already have encountered
				if(exportedConceptPaths.contains(conceptPath)) {
					continue;
				}
				
				ArrayList<String[]> dataEntries = new ArrayList<String[]>();
				PhenoCube<?> cube = getCube(conceptPath);
				List<?> valuesForKeys = cube.getValuesForKeys(idList);
				for (Object kvObj : valuesForKeys) {
					
					KeyAndValue<Double> keyAndValue = (KeyAndValue) kvObj;
					// "PATIENT_NUM","CONCEPT_PATH","NVAL_NUM","TVAL_CHAR","TIMESTAMP"
					String[] entryData = { keyAndValue.getKey().toString(), conceptPath,
							keyAndValue.getValue().toString(), "", keyAndValue.getTimestamp().toString() };
					dataEntries.add(entryData);
				}
				writer.write(out, dataEntries);
				exportedConceptPaths.add(conceptPath);
			}
		}

	}

}
