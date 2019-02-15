package edu.harvard.hms.dbmi.avillach.hpds.data.query;

public interface Filter<T> {
	public boolean apply(T value);

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
	}

}
