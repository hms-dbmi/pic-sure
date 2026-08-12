package edu.harvard.hms.dbmi.avillach.hpds.data.phenotype;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Serial;
import java.io.Serializable;
import java.util.Objects;
import java.util.Set;
import java.util.StringJoiner;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * {@ColumnMeta ColumnMeta} fields that apply globally to a column and can be aggregated across partitioned data.
 */
public class SummaryColumnMeta implements Serializable {

    private static final Logger log = LoggerFactory.getLogger(SummaryColumnMeta.class);

    @Serial
    private static final long serialVersionUID = 1L;

    private String name;
    private int widthInBytes;
    private boolean categorical;
    private Set<String> categoryValues = Set.of();
    private Double min, max;
    private int patientCount;
    private boolean hasTimestamp;
    private Long timestampMin;
    private Long timestampMax;

    public SummaryColumnMeta(ColumnMeta columnMeta) {
        this.name = columnMeta.getName();
        this.widthInBytes = columnMeta.getWidthInBytes();
        this.categorical = columnMeta.isCategorical();
        this.categoryValues = columnMeta.getCategoryValues() != null ? Set.copyOf(columnMeta.getCategoryValues()) : Set.of();
        this.min = columnMeta.getMin();
        this.max = columnMeta.getMax();
        this.patientCount = columnMeta.getPatientCount();
        this.hasTimestamp = columnMeta.hasTimestamp();
        this.timestampMin = columnMeta.getTimestampMin();
        this.timestampMax = columnMeta.getTimestampMax();
    }

    public SummaryColumnMeta() {}

    public SummaryColumnMeta merge(SummaryColumnMeta columnMeta) {
        SummaryColumnMeta newSummaryColumnMeta = new SummaryColumnMeta();

        if (!Objects.equals(this.name, columnMeta.getName())) {
            log.warn("ColumnMeta names do not match: {} and {}", this.name, columnMeta.getName());
        }
        newSummaryColumnMeta.name = this.name;

        if (this.widthInBytes != columnMeta.getWidthInBytes()) {
            log.warn(
                "ColumnMeta {} widthInBytes values do not match: {} and {}", this.name, this.widthInBytes, columnMeta.getWidthInBytes()
            );
        }
        newSummaryColumnMeta.widthInBytes = Integer.max(this.widthInBytes, columnMeta.getWidthInBytes());

        if (this.categorical != columnMeta.isCategorical()) {
            log.warn("ColumnMeta {} categorical values do not match: {} and {}", this.name, this.categorical, columnMeta.isCategorical());
        }
        newSummaryColumnMeta.categorical = this.categorical;

        newSummaryColumnMeta.categoryValues = Stream.concat(
            categoryValues != null ? categoryValues.stream() : Stream.of(),
            columnMeta.getCategoryValues() != null ? columnMeta.getCategoryValues().stream() : Stream.of()
        ).collect(Collectors.toSet());

        if (columnMeta.getMin() != null) {
            newSummaryColumnMeta.min = this.min == null ? columnMeta.getMin() : Double.min(this.min, columnMeta.getMin());
        } else {
            newSummaryColumnMeta.min = this.min;
        }

        if (columnMeta.getMax() != null) {
            newSummaryColumnMeta.max = this.max == null ? columnMeta.getMax() : Double.max(this.max, columnMeta.getMax());
        } else {
            newSummaryColumnMeta.max = this.max;
        }

        // todo: can patients be in different partitions for the same concept path? if so, this will be inaccurate
        newSummaryColumnMeta.patientCount = this.patientCount + columnMeta.getPatientCount();
        newSummaryColumnMeta.hasTimestamp = this.hasTimestamp || columnMeta.hasTimestamp();

        if (columnMeta.getTimestampMin() != null) {
            newSummaryColumnMeta.timestampMin =
                this.timestampMin == null ? columnMeta.getTimestampMin() : Long.min(this.timestampMin, columnMeta.getTimestampMin());
        } else {
            newSummaryColumnMeta.timestampMin = this.timestampMin;
        }

        if (columnMeta.getTimestampMax() != null) {
            newSummaryColumnMeta.timestampMax =
                this.timestampMax == null ? columnMeta.getTimestampMax() : Long.max(this.timestampMax, columnMeta.getTimestampMax());
        } else {
            newSummaryColumnMeta.timestampMax = this.timestampMax;
        }

        return newSummaryColumnMeta;
    }


    public String getName() {
        return name;
    }

    public int getWidthInBytes() {
        return widthInBytes;
    }

    public boolean isCategorical() {
        return categorical;
    }

    public Set<String> getCategoryValues() {
        return categoryValues;
    }

    public Double getMin() {
        return min;
    }

    public Double getMax() {
        return max;
    }

    public int getPatientCount() {
        return patientCount;
    }

    public boolean hasTimestamp() {
        return hasTimestamp;
    }

    public Long getTimestampMin() {
        return timestampMin;
    }

    public Long getTimestampMax() {
        return timestampMax;
    }

    public SummaryColumnMeta setName(String name) {
        this.name = name;
        return this;
    }

    public SummaryColumnMeta setWidthInBytes(int widthInBytes) {
        this.widthInBytes = widthInBytes;
        return this;
    }

    public SummaryColumnMeta setCategorical(boolean categorical) {
        this.categorical = categorical;
        return this;
    }

    public SummaryColumnMeta setCategoryValues(Set<String> categoryValues) {
        this.categoryValues = categoryValues;
        return this;
    }

    public SummaryColumnMeta setMin(Double min) {
        this.min = min;
        return this;
    }

    public SummaryColumnMeta setMax(Double max) {
        this.max = max;
        return this;
    }

    public SummaryColumnMeta setPatientCount(int patientCount) {
        this.patientCount = patientCount;
        return this;
    }

    public SummaryColumnMeta setHasTimestamp(boolean hasTimestamp) {
        this.hasTimestamp = hasTimestamp;
        return this;
    }

    public SummaryColumnMeta setTimestampMin(Long timestampMin) {
        this.timestampMin = timestampMin;
        return this;
    }

    public SummaryColumnMeta setTimestampMax(Long timestampMax) {
        this.timestampMax = timestampMax;
        return this;
    }

    @Override
    public boolean equals(Object o) {
        if (o == null || getClass() != o.getClass()) return false;
        SummaryColumnMeta that = (SummaryColumnMeta) o;
        return widthInBytes == that.widthInBytes && categorical == that.categorical && patientCount == that.patientCount
            && hasTimestamp == that.hasTimestamp && Objects.equals(name, that.name) && Objects.equals(categoryValues, that.categoryValues)
            && Objects.equals(min, that.min) && Objects.equals(max, that.max) && Objects.equals(timestampMin, that.timestampMin)
            && Objects.equals(timestampMax, that.timestampMax);
    }

    @Override
    public int hashCode() {
        return Objects
            .hash(name, widthInBytes, categorical, categoryValues, min, max, patientCount, hasTimestamp, timestampMin, timestampMax);
    }

    @Override
    public String toString() {
        return new StringJoiner(", ", SummaryColumnMeta.class.getSimpleName() + "[", "]").add("name='" + name + "'")
            .add("widthInBytes=" + widthInBytes).add("categorical=" + categorical).add("categoryValues=" + categoryValues).add("min=" + min)
            .add("max=" + max).add("patientCount=" + patientCount).add("hasTimestamp=" + hasTimestamp).add("timestampMin=" + timestampMin)
            .add("timestampMax=" + timestampMax).toString();
    }
}
