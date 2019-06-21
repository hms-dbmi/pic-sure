package edu.harvard.hms.dbmi.avillach.hpds.data.phenotype;

import java.io.Serializable;
import java.util.List;

import org.codehaus.jackson.annotate.JsonIgnore;
import org.codehaus.jackson.map.annotate.JsonSerialize;

@JsonSerialize(include=JsonSerialize.Inclusion.NON_NULL)
public class ColumnMeta implements Serializable{
	
	private static final long serialVersionUID = -124111104912063811L;
	private String name;
	private int widthInBytes;
	private int columnOffset;
	private boolean categorical;
	private List<String> categoryValues;
	private Double min, max;
	private long allObservationsOffset;
	private long allObservationsLength;
	private int observationCount;
	
	public String getName() {
		return name;
	}
	public ColumnMeta setName(String header) {
		this.name = header;
		return this;
	}
	
	@JsonIgnore
	public int getWidthInBytes() {
		return widthInBytes;
	}
	public ColumnMeta setWidthInBytes(int widthInBytes) {
		this.widthInBytes = widthInBytes;
		return this;
	}
	
	@JsonIgnore
	public int getColumnOffset() {
		return columnOffset;
	}
	public ColumnMeta setColumnOffset(int columnOffset) {
		this.columnOffset = columnOffset;
		return this;
	}
	
	public boolean isCategorical() {
		return categorical;
	}
	public ColumnMeta setCategorical(boolean isString) {
		this.categorical = isString;
		return this;
	}
	
	@JsonIgnore
	public long getAllObservationsOffset() {
		return allObservationsOffset;
	}
	public ColumnMeta setAllObservationsOffset(long allObservationsOffset) {
		this.allObservationsOffset = allObservationsOffset;
		return this;
	}
	
	@JsonIgnore
	public long getAllObservationsLength() {
		return allObservationsLength;
	}
	public ColumnMeta setAllObservationsLength(long allObservationsLength) {
		this.allObservationsLength = allObservationsLength;
		return this;
	}
	
	public long getObservationCount() {
		return observationCount;
	}
	public ColumnMeta setObservationCount(int length) {
		this.observationCount = length;
		return this;
	}

	public List<String> getCategoryValues() {
		return categoryValues;
	}
	public void setCategoryValues(List<String> categoryValues) {
		this.categoryValues = categoryValues;
	}

	public Double getMin() {
		return min;
	}
	public void setMin(double min) {
		this.min = min;
	}

	public Double getMax() {
		return max;
	}
	public void setMax(double max) {
		this.max = max;
	}

}
