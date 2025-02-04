package edu.harvard.hms.dbmi.avillach.hpds.etl.phenotype.sequential;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Date;

import org.apache.commons.csv.CSVRecord;

/**
 * This provides a common interface for csv and sql load jobs, which use different indexes.
 * @author nchu
 *
 */
public class PhenoRecord {

	private int patientNum;
	private String conceptPath;
	private String numericValue;
	private String textValue;
	private Date dateTime;
	
	public int getPatientNum() {
		return patientNum;
	}
	public void setPatientNum(int patientNum) {
		this.patientNum = patientNum;
	}
	public String getConceptPath() {
		return conceptPath;
	}
	public void setConceptPath(String conceptPath) {
		this.conceptPath = conceptPath;
	}
	public String getNumericValue() {
		return numericValue;
	}
	public void setNumericValue(String numericValue) {
		this.numericValue = numericValue;
	}
	public String getTextValue() {
		return textValue;
	}
	public void setTextValue(String textValue) {
		this.textValue = textValue;
	}
	public Date getDateTime() {
		return dateTime;
	}
	public void setDateTime(Date dateTime) {
		this.dateTime = dateTime;
	}
	
	
	//SQL uses 1-based arrays
	private static final int PATIENT_NUM_COL_SQL = 1;
	private static final int CONCEPT_PATH_COL_SQL = 2;
	private static final int NUMERIC_VALUE_COL_SQL = 3;
	private static final int TEXT_VALUE_COL_SQL = 4;
	private static final int DATETIME_COL_SQL = 5;
	
	// SQL constructor
	public PhenoRecord (ResultSet result) throws SQLException {
		conceptPath = result.getString(CONCEPT_PATH_COL_SQL);
		dateTime = result.getDate(DATETIME_COL_SQL);
		numericValue = result.getString(NUMERIC_VALUE_COL_SQL);
		patientNum = result.getInt(PATIENT_NUM_COL_SQL);
		textValue = result.getString(TEXT_VALUE_COL_SQL) == null ? null : result.getString(TEXT_VALUE_COL_SQL).trim();
	}
	
	//CSV uses 0-based arrays
	private static final int PATIENT_NUM_COL = 0;
	private static final int CONCEPT_PATH_COL = 1;
	private static final int NUMERIC_VALUE_COL = 2;
	private static final int TEXT_VALUE_COL = 3;
	private static final int DATETIME_COL = 4;
	
	//CSV constructor
	public PhenoRecord (CSVRecord record) {
		conceptPath = record.get(CONCEPT_PATH_COL);
		if(record.size()>DATETIME_COL && record.get(DATETIME_COL) != null && ! record.get(DATETIME_COL).isEmpty()) {
			dateTime =  new Date(Long.parseLong(record.get(DATETIME_COL)));
		}
		numericValue = record.get(NUMERIC_VALUE_COL);
		patientNum = Integer.parseInt(record.get(PATIENT_NUM_COL));
		textValue =record.get(TEXT_VALUE_COL) == null ? null : record.get(TEXT_VALUE_COL).trim();
	}
}
