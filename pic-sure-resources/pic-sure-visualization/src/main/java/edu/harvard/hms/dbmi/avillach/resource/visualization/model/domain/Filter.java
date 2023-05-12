package edu.harvard.hms.dbmi.avillach.resource.visualization.model.domain;

public interface Filter<T> {
    public boolean apply(T value);

    public static class DoubleFilter implements Filter<Double> {

        Double min, max;

        public Double getMin() {
            return min;
        }

        public void setMin(Double min) {
            this.min = min;
        }

        public Double getMax() {
            return max;
        }

        public void setMax(Double max) {
            this.max = max;
        }

        public DoubleFilter() {

        }

        public DoubleFilter(Double min, Double max) {
            this.min = min;
            this.max = max;
        }

        public boolean apply(Double value) {
            return value >= min && value <= max;
        }

        public String toString() {
            String strVal = "";
            if(min != null) {
                strVal = "Greater than " + min;
                if(max != null) {
                    strVal += " and ";
                }
            }
            if(max != null) {
                strVal += "Less than " + max;
            }
            return strVal;
        }
    }
    public static class FloatFilter implements Filter<Float> {

        Float min, max;

        public Float getMin() {
            return min;
        }

        public void setMin(Float min) {
            this.min = min;
        }

        public Float getMax() {
            return max;
        }

        public void setMax(Float max) {
            this.max = max;
        }

        public FloatFilter() {

        }

        public FloatFilter(Float min, Float max) {
            this.min = min;
            this.max = max;
        }

        public boolean apply(Float value) {
            return value >= min && value <= max;
        }

        public String toString() {
            String strVal = "";
            if(min != null) {
                strVal = "Greater than " + min;
                if(max != null) {
                    strVal += " and ";
                }
            }
            if(max != null) {
                strVal += "Less than " + max;
            }
            return strVal;
        }
    }

}

