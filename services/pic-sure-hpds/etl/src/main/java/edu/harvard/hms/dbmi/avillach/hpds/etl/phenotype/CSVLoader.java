package edu.harvard.hms.dbmi.avillach.hpds.etl.phenotype;

import java.io.*;
import java.util.Date;
import java.util.concurrent.ExecutionException;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.cache.CacheLoader.InvalidCacheLoadException;

import edu.harvard.hms.dbmi.avillach.hpds.crypto.Crypto;
import edu.harvard.hms.dbmi.avillach.hpds.data.phenotype.PhenoCube;

@SuppressWarnings({"unchecked", "rawtypes"})
public class CSVLoader {

	private static LoadingStore store = new LoadingStore();

	private static Logger log = LoggerFactory.getLogger(CSVLoader.class); 

	private static final int PATIENT_NUM = 0;

	private static final int CONCEPT_PATH = 1;

	private static final int NUMERIC_VALUE = 2;

	private static final int TEXT_VALUE = 3;

	private static final int DATETIME = 4;

	private static String HPDS_DIRECTORY = "/opt/local/hpds/";

	public static void main(String[] args) throws IOException {
		if (args.length > 0) {
			HPDS_DIRECTORY = args[0] + "/";
		}
		store.allObservationsStore = new RandomAccessFile(HPDS_DIRECTORY + "allObservationsStore.javabin", "rw");
		initialLoad(HPDS_DIRECTORY);
		store.saveStore(HPDS_DIRECTORY);
	}

	private static void initialLoad(String hpdsDirectory) throws IOException {
		Crypto.loadKey(Crypto.DEFAULT_KEY_NAME, hpdsDirectory + "encryption_key");
		Reader in = new FileReader(HPDS_DIRECTORY + "allConcepts.csv");
		Iterable<CSVRecord> records = CSVFormat.DEFAULT.withSkipHeaderRecord().withFirstRecordAsHeader().parse(new BufferedReader(in, 1024*1024));

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
			String conceptPathFromRow = record.get(CONCEPT_PATH);
			String[] segments = conceptPathFromRow.split("\\\\");
			for(int x = 0;x<segments.length;x++) {
				segments[x] = segments[x].trim();
			}
			conceptPathFromRow = String.join("\\", segments) + "\\";
			conceptPathFromRow = conceptPathFromRow.replaceAll("\\ufffd", "");
			String textValueFromRow = record.get(TEXT_VALUE) == null ? null : record.get(TEXT_VALUE).trim();
			if(textValueFromRow!=null) {
				textValueFromRow = textValueFromRow.replaceAll("\\ufffd", "");
			}
			String conceptPath = conceptPathFromRow.endsWith("\\" +textValueFromRow+"\\") ? conceptPathFromRow.replaceAll("\\\\[^\\\\]*\\\\$", "\\\\") : conceptPathFromRow;
			// This is not getDouble because we need to handle null values, not coerce them into 0s
			String numericValue = record.get(NUMERIC_VALUE);
			if((numericValue==null || numericValue.isEmpty()) && textValueFromRow!=null) {
				try {
					numericValue = Double.parseDouble(textValueFromRow) + "";
				}catch(NumberFormatException e) {

				}
			}
			boolean isAlpha = (numericValue == null || numericValue.isEmpty());
			if(currentConcept[0] == null || !currentConcept[0].name.equals(conceptPath)) {
				System.out.println(conceptPath);
				try {
					currentConcept[0] = store.store.get(conceptPath);
				} catch(InvalidCacheLoadException e) {
					currentConcept[0] = new PhenoCube(conceptPath, isAlpha ? String.class : Double.class);
					store.store.put(conceptPath, currentConcept[0]);
				}
			}
			String value = isAlpha ? record.get(TEXT_VALUE) : numericValue;

			if(value != null && !value.trim().isEmpty() && ((isAlpha && currentConcept[0].vType == String.class)||(!isAlpha && currentConcept[0].vType == Double.class))) {
				value = value.trim();
				currentConcept[0].setColumnWidth(isAlpha ? Math.max(currentConcept[0].getColumnWidth(), value.getBytes().length) : Double.BYTES);
				int patientId = Integer.parseInt(record.get(PATIENT_NUM));
				Date date = null;
				if(record.size()>4 && record.get(DATETIME) != null && ! record.get(DATETIME).isEmpty()) {
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
}
