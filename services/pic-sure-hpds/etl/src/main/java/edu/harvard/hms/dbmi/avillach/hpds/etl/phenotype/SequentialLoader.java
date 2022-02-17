package edu.harvard.hms.dbmi.avillach.hpds.etl.phenotype;

import java.io.*;
import java.nio.charset.Charset;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.ExecutionException;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVRecord;
import org.apache.commons.io.IOUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
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

	private static SequentialLoadingStore store = new SequentialLoadingStore();

	private static Logger log = LoggerFactory.getLogger(SequentialLoader.class); 

	private static JdbcTemplate template = null;

	public static void main(String[] args) throws IOException {
		
		Crypto.loadDefaultKey();
		
		List<String> inputFiles = new ArrayList<String>();
		//read in input files
		if(args.length > 0) {
			inputFiles.addAll(Arrays.asList(args));
		} else {
			inputFiles.addAll(readFileList());
		}
		
		if(inputFiles.size() == 0) {
			// check for "/opt/local/hpds/loadQuery.sql" first
			 File file = new File("/opt/local/hpds/loadQuery.sql");
			 if(file.isFile()) {
				 inputFiles.add("/opt/local/hpds/loadQuery.sql");
			 } else {
				 file = new File("/opt/local/hpds/allConcepts.csv");
				 if(file.isFile()) {
					inputFiles.add("/opt/local/hpds/allConcepts.csv");
				 }
			 }
		}
		
		//load each into observation store
		for(String filename : inputFiles) {
			if(filename.toLowerCase().endsWith("sql")) {
				loadSqlFile(filename);
			} else {
				loadCsvFile(filename);
			}
		}
		
		//then complete, which will compact, sort, and write out the data in the final place
		try {
			store.saveStore();
			store.dumpStats();
		} catch (ClassNotFoundException e) {
			System.out.println("Class error: " + e.getLocalizedMessage());
			e.printStackTrace();
		}
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

	private static void loadCsvFile(String filename) throws IOException {
		
		Reader in = new FileReader(filename);
		BufferedReader reader = new BufferedReader(in, 1024*1024);
		Iterable<CSVRecord> records = CSVFormat.DEFAULT.withSkipHeaderRecord().withFirstRecordAsHeader().parse(reader);

		//currentConcept is used to persist data across function calls and identify when we hit a new concept
		final PhenoCube[] currentConcept = new PhenoCube[1];
		for (CSVRecord record : records) {
			if(record.size()<4) {
				log.info("Record number " + record.getRecordNumber() 
				+ " had less records than we expected so we are skipping it.");
				continue;
			} 
			processRecord(currentConcept, new PhenoRecord(record));
		}
		reader.close();
		in.close();
	}
	
	private static void loadSqlFile(String filename) throws FileNotFoundException, IOException {
		loadTemplate();
		
		String loadQuery = IOUtils.toString(new FileInputStream(filename), Charset.forName("UTF-8"));
		
		//currentConcept is used to persist data across function calls and identify when we hit a new concept
		final PhenoCube[] currentConcept = new PhenoCube[1];
		
		template.query(loadQuery, new RowCallbackHandler() {

			@Override
			public void processRow(ResultSet result) throws SQLException {
				int row = result.getRow();
				if(row%100000==0) {
					System.out.println(row);
				}
				
				processRecord(currentConcept, new PhenoRecord(result));
			}
		});

	}

	
	private static void loadTemplate() throws FileNotFoundException, IOException {
		if (template == null) {
			Properties props = new Properties();

			props.load(new FileInputStream("/opt/local/hpds/sql.properties"));
			template = new JdbcTemplate(new DriverManagerDataSource(props.getProperty("datasource.url"), props.getProperty("datasource.user"),
					props.getProperty("datasource.password")));
		}
	}

	private static void processRecord(final PhenoCube[] currentConcept, PhenoRecord record) {
		if(record == null ) {
			return;
		}

		try {
			String conceptPathFromRow = record.getConceptPath();
			String[] segments = conceptPathFromRow.split("\\\\");
			for(int x = 0;x<segments.length;x++) {
				segments[x] = segments[x].trim();
			}
			conceptPathFromRow = String.join("\\", segments) + "\\";
			conceptPathFromRow = conceptPathFromRow.replaceAll("\\ufffd", "");
			String textValueFromRow = record.getTextValue();
			if(textValueFromRow!=null) {
				textValueFromRow = textValueFromRow.replaceAll("\\ufffd", "");
			}
			String conceptPath = conceptPathFromRow.endsWith("\\" +textValueFromRow+"\\") ? conceptPathFromRow.replaceAll("\\\\[^\\\\]*\\\\$", "\\\\") : conceptPathFromRow;
			// This is not getDouble because we need to handle null values, not coerce them into 0s
			String numericValue = record.getNumericValue();
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
			String value = isAlpha ? record.getTextValue() : numericValue;

			if(value != null && !value.trim().isEmpty() && ((isAlpha && currentConcept[0].vType == String.class)||(!isAlpha && currentConcept[0].vType == Double.class))) {
				value = value.trim();
				currentConcept[0].setColumnWidth(isAlpha ? Math.max(currentConcept[0].getColumnWidth(), value.getBytes().length) : Double.BYTES);
				int patientId = record.getPatientNum();
				
				currentConcept[0].add(patientId, isAlpha ? value : Double.parseDouble(value), record.getDateTime());
				store.allIds.add(patientId);
			}
		} catch (ExecutionException e) {
			e.printStackTrace();
		}
	}
}
