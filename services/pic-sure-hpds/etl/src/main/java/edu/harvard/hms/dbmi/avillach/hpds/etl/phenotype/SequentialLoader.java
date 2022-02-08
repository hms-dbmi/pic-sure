package edu.harvard.hms.dbmi.avillach.hpds.etl.phenotype;

import java.io.*;
import java.util.*;
import java.util.concurrent.ExecutionException;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

	private static SequentialLoadingStore store = new SequentialLoadingStore();

	private static Logger log = LoggerFactory.getLogger(SequentialLoader.class); 

	private static final int PATIENT_NUM = 0;

	private static final int CONCEPT_PATH = 1;

	private static final int NUMERIC_VALUE = 2;

	private static final int TEXT_VALUE = 3;

	private static final int DATETIME = 4;

	public static void main(String[] args) throws IOException {
		
		Crypto.loadDefaultKey();
		store.allObservationsStore = new RandomAccessFile(SequentialLoadingStore.OBSERVATIONS_FILENAME, "rw");
		
		List<String> inputFiles = new ArrayList<String>();
		//read in input files
		if(args.length > 0) {
			inputFiles.addAll(Arrays.asList(args));
		} else {
			inputFiles.addAll(readFileList());
		}
			
		if(inputFiles.size() == 0) {
			inputFiles.add("/opt/local/hpds/allConcepts.csv");
		}
			
		
		//load each into observation store
		
		for(String filename : inputFiles) {
			loadFile(filename);
		}
		
		store.saveStore();
	}

	private static List<? extends String> readFileList() {
		
		BufferedReader br;
		try {
			br = new BufferedReader(new FileReader("/opt/local/hpds/phenotypeInputs.txt"));
		} catch (FileNotFoundException e) {
			return new ArrayList<String>();
		}
		
		List<String> inputFiles = new ArrayList<String>();
		
		try {
		    String line = br.readLine();
		    while (line != null) {
		    	inputFiles.add(line);
		        line = br.readLine();
		    }
		    br.close();
		}catch (IOException e) {
			e.printStackTrace();
		}
		
		return inputFiles;
	}

	private static void loadFile(String filename) throws IOException {
		
		Reader in = new FileReader(filename);
		BufferedReader reader = new BufferedReader(in, 1024*1024);
		Iterable<CSVRecord> records = CSVFormat.DEFAULT.withSkipHeaderRecord().withFirstRecordAsHeader().parse(reader);

		//currentConcept is used to persist data across function calls
		final PhenoCube[] currentConcept = new PhenoCube[1];
		for (CSVRecord record : records) {
			processRecord(currentConcept, record);
		}
		reader.close();
		in.close();
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
			e.printStackTrace();
		}
	}
}
