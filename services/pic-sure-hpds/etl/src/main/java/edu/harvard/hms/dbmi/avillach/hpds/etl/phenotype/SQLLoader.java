package edu.harvard.hms.dbmi.avillach.hpds.etl.phenotype;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.io.*;
import java.util.Properties;
import java.util.concurrent.ExecutionException;

import com.google.common.cache.CacheLoader.InvalidCacheLoadException;

import edu.harvard.hms.dbmi.avillach.hpds.data.phenotype.PhenoCube;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

public class SQLLoader {

	static JdbcTemplate template;

	private static LoadingStore store = new LoadingStore();

	private static final int PATIENT_NUM = 1;

	private static final int CONCEPT_PATH = 2;

	private static final int NUMERIC_VALUE = 3;

	private static final int TEXT_VALUE = 4;

	private static final Properties props = new Properties();
	
	public static void main(String[] args) throws IOException {
		props.load(new FileInputStream("/opt/local/hpds/sql.properties"));
		template = new JdbcTemplate(new DriverManagerDataSource(prop("datasource.url"), prop("datasource.user"), prop("datasource.password")));
		store.allObservationsStore = new RandomAccessFile("/opt/local/hpds/allObservationsStore.javabin", "rw");
		initialLoad();
		store.saveStore();
	}

	private static String prop(String key) {
		return props.getProperty(key);
	}

	private static void initialLoad() throws IOException {
		final PhenoCube[] currentConcept = new PhenoCube[1];
		template.query("    select ofact.PATIENT_NUM, CONCEPT_PATH, NVAL_NUM, TVAL_CHAR \n" + 
				"    FROM i2b2demodata.OBSERVATION_FACT ofact\n" + 
				"    JOIN i2b2demodata.CONCEPT_DIMENSION cd \n" + 
				"    ON cd.CONCEPT_CD=ofact.CONCEPT_CD \n" + 
				"    ORDER BY CONCEPT_PATH, ofact.PATIENT_NUM", new RowCallbackHandler() {

			@Override
			public void processRow(ResultSet arg0) throws SQLException {
				int row = arg0.getRow();
				if(row%100000==0) {
					System.out.println(row);
				}
				processRecord(currentConcept, arg0);
			}

		});
	}

	private static void processRecord(final PhenoCube[] currentConcept, ResultSet arg0) {
		try {
			String conceptPathFromRow = arg0.getString(CONCEPT_PATH);
			String[] segments = conceptPathFromRow.split("\\\\");
			for(int x = 0;x<segments.length;x++) {
				segments[x] = segments[x].trim();
			}
			conceptPathFromRow = String.join("\\", segments) + "\\";
			conceptPathFromRow = conceptPathFromRow.replaceAll("\\ufffd", "");
			String textValueFromRow = arg0.getString(TEXT_VALUE) == null ? null : arg0.getString(TEXT_VALUE).trim();
			if(textValueFromRow!=null) {
				textValueFromRow = textValueFromRow.replaceAll("\\ufffd", "");
			}
			String conceptPath = conceptPathFromRow.endsWith("\\" +textValueFromRow+"\\") ? conceptPathFromRow.replaceAll("\\\\[^\\\\]*\\\\$", "\\\\") : conceptPathFromRow;
			// This is not getDouble because we need to handle null values, not coerce them into 0s
			String numericValue = arg0.getString(NUMERIC_VALUE);
			if((numericValue==null || numericValue.isEmpty()) && textValueFromRow!=null) {
				try {
					numericValue = Float.parseFloat(textValueFromRow) + "";
				}catch(NumberFormatException e) {
					
				}
			}
			boolean isAlpha = (numericValue == null || numericValue.isEmpty());
			if(currentConcept[0] == null || !currentConcept[0].name.equals(conceptPath)) {
				System.out.println(conceptPath);
				try {
					currentConcept[0] = store.store.get(conceptPath);
				} catch(InvalidCacheLoadException e) {
					currentConcept[0] = new PhenoCube(conceptPath, isAlpha ? String.class : Float.class);
					store.store.put(conceptPath, currentConcept[0]);
				}
			}
			String value = isAlpha ? arg0.getString(TEXT_VALUE) : numericValue;

			if(value != null && !value.trim().isEmpty() && ((isAlpha && currentConcept[0].vType == String.class)||(!isAlpha && currentConcept[0].vType == Float.class))) {
				value = value.trim();
				currentConcept[0].setColumnWidth(isAlpha ? Math.max(currentConcept[0].getColumnWidth(), value.getBytes().length) : Float.BYTES);
				int patientId = arg0.getInt(PATIENT_NUM);
				currentConcept[0].add(patientId, isAlpha ? value : Float.parseFloat(value));
				store.allIds.add(patientId);
			}
		} catch (ExecutionException e) {
			e.printStackTrace();
		} catch (SQLException e2) {
			// TODO Auto-generated catch block
			e2.printStackTrace();
		}
	}
}