package edu.harvard.hms.dbmi.avillach.hpds.data.phenotype;

public class TimelineEvent {
	public TimelineEvent(KeyAndValue value2, long startTime) {
		this.timestamp = value2.timestamp;
		this.patient_num = value2.key;
		this.value = value2.value.toString();
	}

	long timestamp;
	int patient_num;
	String value;
	
	public long getTimestamp() {
		return timestamp;
	}
	public void setTimestamp(long timestamp) {
		this.timestamp = timestamp;
	}
	public int getPatient_num() {
		return patient_num;
	}
	public void setPatient_num(int patient_num) {
		this.patient_num = patient_num;
	}
	public String getValue() {
		return value;
	}
	public void setValue(String value) {
		this.value = value;
	}
}
