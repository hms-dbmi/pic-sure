package edu.harvard.hms.dbmi.avillach.hpds.data.phenotype;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.io.Serializable;
import java.util.List;


@JsonInclude(JsonInclude.Include.NON_NULL)
public class ColumnMeta implements Serializable {

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
    private int patientCount;

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

    public ColumnMeta setCategorical(boolean isCategorical) {
        this.categorical = isCategorical;
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

    public long getPatientCount() {
        return patientCount;
    }

    public ColumnMeta setPatientCount(int length) {
        this.patientCount = length;
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

    public ColumnMeta setMin(double min) {
        this.min = min;
        return this;
    }

    public Double getMax() {
        return max;
    }

    public ColumnMeta setMax(double max) {
        this.max = max;
        return this;
    }

}
