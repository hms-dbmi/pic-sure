package edu.harvard.hms.dbmi.avillach.pheno;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.io.*;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;

import org.apache.log4j.Logger;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.CacheLoader.InvalidCacheLoadException;

import edu.harvard.hms.dbmi.avillach.pheno.crypto.Crypto;
import edu.harvard.hms.dbmi.avillach.pheno.data.ColumnMeta;
import edu.harvard.hms.dbmi.avillach.pheno.data.KeyAndValue;
import edu.harvard.hms.dbmi.avillach.pheno.data.PhenoCube;

import com.google.common.cache.LoadingCache;
import com.google.common.cache.RemovalListener;
import com.google.common.cache.RemovalNotification;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import static edu.harvard.hms.dbmi.avillach.pheno.LoadingStore.*;

public class SQLLoader {
	
	static JdbcTemplate template;
	
	public SQLLoader() {
		template = new JdbcTemplate(new DriverManagerDataSource("jdbc:oracle:thin:@192.168.99.101:1521/ORCLPDB1", "pdbadmin", "password"));
	}

	private static LoadingStore store = new LoadingStore();

	private static Logger log = Logger.getLogger(SQLLoader.class);

	private static final int PATIENT_NUM = 1;

	private static final int CONCEPT_PATH = 2;

	private static final int NUMERIC_VALUE = 3;

	private static final int TEXT_VALUE = 4;

	public static void main(String[] args) throws IOException {
		template = new JdbcTemplate(new DriverManagerDataSource("jdbc:oracle:thin:@192.168.99.101:1521/ORCLPDB1", "pdbadmin", "password"));
		store.allObservationsStore = new RandomAccessFile("/tmp/allObservationsStore.javabin", "rw");
		initialLoad();
		store.saveStore();
	}

	private static void initialLoad() throws IOException {
		final PhenoCube[] currentConcept = new PhenoCube[1];
		template.query("select psc.PATIENT_NUM, CONCEPT_PATH, NVAL_NUM, TVAL_CHAR "
				+ "FROM i2b2demodata.QT_PATIENT_SET_COLLECTION psc "
				+ "LEFT JOIN i2b2demodata.OBSERVATION_FACT ofact "
				+ "ON ofact.PATIENT_NUM=psc.PATIENT_NUM "
				+ "JOIN i2b2demodata.CONCEPT_DIMENSION cd "
				+ "ON cd.CONCEPT_CD=ofact.CONCEPT_CD "
				+ "WHERE RESULT_INSTANCE_ID=41 ORDER BY CONCEPT_PATH, psc.PATIENT_NUM", new RowCallbackHandler() {

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
			String textValueFromRow = arg0.getString(TEXT_VALUE) == null ? null : arg0.getString(TEXT_VALUE).trim();
			String conceptPath = conceptPathFromRow.endsWith("\\" +textValueFromRow+"\\") ? conceptPathFromRow.replaceAll("\\\\[\\w\\.-]*\\\\$", "\\\\") : conceptPathFromRow;
			// This is not getDouble because we need to handle null values, not coerce them into 0s
			String numericValue = arg0.getString(NUMERIC_VALUE);
			if(numericValue==null || numericValue.isEmpty()) {
				try {
					numericValue = Float.parseFloat(textValueFromRow) + "";
				}catch(NumberFormatException e) {
					try {
						log.info("Record number " + arg0.getRow() 
						+ " had an alpha value where we expected a number in the alpha column... "
						+ "which sounds weirder than it really is.");
					} catch (SQLException e1) {
						// TODO Auto-generated catch block
						e1.printStackTrace();
					}

				}
			}
			boolean isAlpha = (numericValue == null || numericValue.isEmpty());
			if(currentConcept[0] == null || !currentConcept[0].name.equals(conceptPath)) {
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
				currentConcept[0].add(arg0.getInt(PATIENT_NUM), isAlpha ? value : Float.parseFloat(value));
			}
		} catch (ExecutionException e) {
			e.printStackTrace();
		} catch (SQLException e2) {
			// TODO Auto-generated catch block
			e2.printStackTrace();
		}
	}
}