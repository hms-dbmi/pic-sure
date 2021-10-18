package edu.harvard.hms.dbmi.avillach.resource.passthru;

import java.io.Serializable;
import java.util.HashSet;
import java.util.Set;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;

@JsonSerialize(include=JsonSerialize.Inclusion.NON_NULL)
public class SearchColumnMeta implements Serializable{
	
	private static final long serialVersionUID = -1426704684157517837L;
	private String name;
	private Boolean categorical;
	private Set<String> categoryValues;
	private Double min, max;
//	private int observationCount;
//	private int patientCount;
	
	/**
	 * Shows what resource this concept is available in
	 */
	private Set<String> resourceAvailability = new HashSet<String>();
	//for info column meta
	private String description;
	
	public String getName() {
		return name;
	}
	public SearchColumnMeta setName(String header) {
		this.name = header;
		return this;
	}
	
	public Boolean isCategorical() {
		return categorical;
	}
	public SearchColumnMeta setCategorical(boolean isString) {
		this.categorical = isString;
		return this;
	}
//	
//	public long getObservationCount() {
//		return observationCount;
//	}
//	public SearchColumnMeta setObservationCount(int length) {
//		this.observationCount = length;
//		return this;
//	}
//
//	public long getPatientCount() {
//		return patientCount;
//	}
//	public SearchColumnMeta setPatientCount(int length) {
//		this.patientCount = length;
//		return this;
//	}

	public Set<String> getCategoryValues() {
		return categoryValues;
	}
	public SearchColumnMeta setCategoryValues(Set<String> categoryValues) {
		this.categoryValues = categoryValues;
		return this;
	}

	public Double getMin() {
		return min;
	}
	public SearchColumnMeta setMin(double min) {
		this.min = min;
		return this;
	}

	public Double getMax() {
		return max;
	}
	public SearchColumnMeta setMax(double max) {
		this.max = max;
		return this;
	}
	public Set<String> getResourceAvailability() {
		return resourceAvailability;
	}
	public SearchColumnMeta setResourceAvailability(Set<String> resourceAvailability) {
		this.resourceAvailability = resourceAvailability;
		return this;
	}
	public String getDescription() {
		return description;
	}
	public SearchColumnMeta setDescription(String description) {
		this.description = description;
		return this;
	}

}
