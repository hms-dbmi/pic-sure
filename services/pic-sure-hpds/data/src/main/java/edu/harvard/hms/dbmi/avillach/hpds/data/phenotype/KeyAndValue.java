package edu.harvard.hms.dbmi.avillach.hpds.data.phenotype;

import java.io.Serializable;

public class KeyAndValue<V extends Comparable<V>> implements Serializable, Comparable<KeyAndValue<?>> {

	private static final long serialVersionUID = 6467549952930943858L;
	
	int key;

	V value;
	
	Long timestamp;
		
	public KeyAndValue() {
		
	}

	public KeyAndValue(int key, V value) {
		this.key = key;
		this.value = value;
		this.setTimestamp(Long.MIN_VALUE);
	}


	public KeyAndValue(int key, V value, long timestamp) {
		this.key = key;
		this.value = value;
		this.setTimestamp(timestamp);
	}

	public V getValue() {
		return value;
	}

	public KeyAndValue<V> setValue(V value) {
		this.value = value;
		return this;
	}

	public Integer getKey() {
		return key;
	}

	public KeyAndValue<V> setKey(Integer key) {
		this.key = key;
		return this;
	}

	@Override
	public int compareTo(KeyAndValue<?> o) {
		return o.key - this.key;
	}

	public Long getTimestamp() {
		return timestamp;
	}

	public void setTimestamp(Long timestamp) {
		this.timestamp = timestamp;
	}
	
}
