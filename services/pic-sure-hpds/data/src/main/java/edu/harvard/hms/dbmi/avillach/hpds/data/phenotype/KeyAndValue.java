package edu.harvard.hms.dbmi.avillach.hpds.data.phenotype;

import java.io.Serializable;

public class KeyAndValue<V extends Comparable<V>> implements Serializable, Comparable<KeyAndValue<?>> {

	private static final long serialVersionUID = 6467549952930943858L;
	
	int key;

	V value;
	
	long timestamp;
		
	public KeyAndValue() {
		
	}

	public KeyAndValue(int key, V value) {
		this.key = key;
		this.value = value;
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
		return this.key - o.key;
	}
}
