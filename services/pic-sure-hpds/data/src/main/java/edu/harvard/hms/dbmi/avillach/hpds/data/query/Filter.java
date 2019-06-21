package edu.harvard.hms.dbmi.avillach.hpds.data.query;

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
	}

}
