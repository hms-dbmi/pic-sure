package edu.harvard.hms.dbmi.avillach.pheno;

import java.io.*;
import java.util.concurrent.ExecutionException;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVRecord;
import org.apache.log4j.Logger;

import com.google.common.cache.CacheLoader.InvalidCacheLoadException;

import edu.harvard.hms.dbmi.avillach.pheno.data.PhenoCube;

import static edu.harvard.hms.dbmi.avillach.pheno.LoadingStore.*;

@SuppressWarnings({"unchecked", "rawtypes"})
public class CSVLoader {

	private static Logger log = Logger.getLogger(CSVLoader.class);

	private static final int PATIENT_NUM = 0;

	private static final int CONCEPT_PATH = 1;

	private static final int NUMERIC_VALUE = 2;

	private static final int TEXT_VALUE = 3;

	public static void main(String[] args) throws IOException {
		allObservationsStore = new RandomAccessFile("/opt/local/phenocube/allObservationsStore.javabin", "rw");
		initialLoad();
		saveStore();
	}

	private static void initialLoad() throws IOException {
		Reader in = new FileReader("/opt/local/phenocube/allConcepts.csv");
		Iterable<CSVRecord> records = CSVFormat.DEFAULT.withSkipHeaderRecord().withFirstRecordAsHeader().parse(in);

		final PhenoCube[] currentConcept = new PhenoCube[1];
		for (CSVRecord record : records) {
			processRecord(currentConcept, record);
		}
	}

	private static void processRecord(final PhenoCube[] currentConcept, CSVRecord record) {
		if(record.size()<4) {
			log.info("Record number " + record.getRecordNumber() 
			+ " had less records than we expected so we are skipping it.");
			return;
		}

		try {
			String conceptPath = record.get(CONCEPT_PATH).endsWith("\\" +record.get(TEXT_VALUE).trim()+"\\") ? record.get(CONCEPT_PATH).replaceAll("\\\\[\\w\\.-]*\\\\$", "\\\\") : record.get(CONCEPT_PATH);
			String numericValue = record.get(NUMERIC_VALUE);
			if(numericValue==null || numericValue.isEmpty()) {
				try {
					numericValue = Float.parseFloat(record.get(TEXT_VALUE).trim()) + "";
				}catch(NumberFormatException e) {
					log.info("Record number " + record.getRecordNumber() 
					+ " had an alpha value where we expected a number in the alpha column... "
					+ "which sounds weirder than it really is.");

				}
			}
			boolean isAlpha = (numericValue == null || numericValue.isEmpty());
			if(currentConcept[0] == null || !currentConcept[0].name.equals(conceptPath)) {
				try {
					currentConcept[0] = store.get(conceptPath);
				} catch(InvalidCacheLoadException e) {
					currentConcept[0] = new PhenoCube(conceptPath, isAlpha ? String.class : Float.class);
					store.put(conceptPath, currentConcept[0]);
				}
			}
			String value = isAlpha ? record.get(TEXT_VALUE) : numericValue;

			if(value != null && !value.trim().isEmpty() && ((isAlpha && currentConcept[0].vType == String.class)||(!isAlpha && currentConcept[0].vType == Float.class))) {
				value = value.trim();
				currentConcept[0].setColumnWidth(isAlpha ? Math.max(currentConcept[0].getColumnWidth(), value.getBytes().length) : Float.BYTES);
				currentConcept[0].add(Integer.parseInt(record.get(PATIENT_NUM).trim()), isAlpha ? value : Float.parseFloat(value));
			}
		} catch (ExecutionException e) {
			e.printStackTrace();
		}
	}
}
