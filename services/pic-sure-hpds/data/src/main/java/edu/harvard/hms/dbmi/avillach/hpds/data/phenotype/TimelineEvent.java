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
}
