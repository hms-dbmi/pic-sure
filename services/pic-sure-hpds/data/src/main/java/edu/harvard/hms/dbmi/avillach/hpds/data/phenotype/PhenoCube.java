package edu.harvard.hms.dbmi.avillach.hpds.data.phenotype;

import java.io.Serializable;
import java.lang.reflect.Array;
import java.util.*;
import java.util.stream.Collectors;

public class PhenoCube<V extends Comparable<V>> implements Serializable {

	private static final long serialVersionUID = -2584728054717230390L;
	public final String name;
	public final Class<V> vType;

	int columnWidth;

	V[] lowDimValues; 
	boolean isLowDim = false;

	private KeyAndValue<V>[] sortedByKey;
	
	private TreeMap<V, TreeSet<Integer>> categoryMap;

	private transient List<KeyAndValue<V>> loadingMap = Collections.synchronizedList(new ArrayList<>());

	public PhenoCube(String name, Class<V> vType){
		this.vType = vType;
		this.name = name;
	}

	public void add(Integer key, V value, Date date) {
		loadingMap.add(new KeyAndValue<V>(key, value, date!=null?date.getTime():null));
	}
	
	public V getValueForKey(Integer key) {
		int idx = Arrays.binarySearch(sortedByKey(), new KeyAndValue<V>(key, null));
		if(idx < 0) return null;
		return sortedByKey()[idx].value;
	}

	public Set<Integer> getKeysForValue(V value) {
		
		if(isStringType()) {
			Set<Integer> keys = categoryMap.get(value);
			return keys == null ? new TreeSet<Integer>() : keys;
		} else {
			int minIndex;
			KeyAndValue<V> keyAndValue = new KeyAndValue<V>(1, value);
			
			if(value == null) {
				return new TreeSet<Integer>();
			} else {
				KeyAndValue<V>[] sortedByValue = sortedByValue();

				int minSearchIndex = Arrays.binarySearch(sortedByValue, keyAndValue, (a,b)->{
					return a.value.compareTo(b.value);
				});
				minIndex = seekForMinIndex(Math.abs(minSearchIndex), keyAndValue, sortedByValue);

				List<Integer> keys = new ArrayList<Integer>();
				for(int x = minIndex; x < sortedByValue.length && value.equals(sortedByValue[x].value); x++) {
					keys.add(sortedByValue[x].key);
				}

				return new TreeSet<Integer> (keys);
			}
		}
		
	}

	public TreeSet<Integer> getKeysForRange(V min, V max) {
		KeyAndValue<V>[] entries = getEntriesForValueRange(min, max);
		TreeSet<Integer> keys = new TreeSet<>();
		for(KeyAndValue<V> entry : entries) {
			keys.add(entry.key);
		}
		return keys;
	}

	public V[] getValuesForRange(V min, V max) {
		KeyAndValue<V>[] entries = getEntriesForValueRange(min, max);
		@SuppressWarnings("unchecked")
		V[] values = (V[]) Array.newInstance(vType, entries.length);
		for(int x = 0;x<entries.length;x++) {
			values[x] = entries[x].value;
		}
		return values;
	}

	public KeyAndValue<V>[] getEntriesForValueRange(V min, V max) {
		KeyAndValue<V> minKeyAndValue = new KeyAndValue<V>(1, min);
		KeyAndValue<V> maxKeyAndValue = new KeyAndValue<V>(2, max);

		int minIndex;
		int maxIndex;
		
		KeyAndValue<V>[] sortedByValue = sortedByValue();
		if(min == null) {
			minIndex = 0;
		} else {
			int minSearchIndex = Arrays.binarySearch(sortedByValue, minKeyAndValue, (a,b)->{
				return a.value.compareTo(b.value);
			});
			minIndex = seekForMinIndex(Math.abs(minSearchIndex), minKeyAndValue, sortedByValue);
		}
		
		if(max == null) {
			maxIndex = sortedByValue.length;
		} else {
			int maxSearchIndex = Arrays.binarySearch(sortedByValue, maxKeyAndValue, (a,b)->{
				return a.value.compareTo(b.value);
			});
			maxIndex = seekForMaxIndex(Math.abs(maxSearchIndex), maxKeyAndValue, sortedByValue);
		}
		
		return Arrays.copyOfRange(sortedByValue, minIndex, maxIndex);
	}

	private int seekForMinIndex(int minSearchIndex, KeyAndValue<V> minEntry, KeyAndValue<V>[] sortedByValue) {
		Comparator<KeyAndValue<V>> comparator = (a,b)->{
			return a.value.compareTo(b.value);
		};
		while(minSearchIndex > -1 && comparator.compare(sortedByValue[minSearchIndex], minEntry)>=0) {
			minSearchIndex--;
		}
		return Math.max(0, minSearchIndex+1);
	}

	private int seekForMaxIndex(int maxSearchIndex, KeyAndValue<V> maxEntry, KeyAndValue<V>[] sortedByValue) {
		Comparator<KeyAndValue<V>> comparator = (a,b)->{
			return a.value.compareTo(b.value);
		};
		while(maxSearchIndex < sortedByValue.length && comparator.compare(maxEntry, sortedByValue[maxSearchIndex])>=0) {
			maxSearchIndex++;
		}
		return maxSearchIndex;
	}

	public boolean isStringType() {
		return vType.equals(String.class);
	}

	public KeyAndValue<V>[] sortedByValue() {
		KeyAndValue<V>[] sortedByValue = Arrays.copyOf(sortedByKey(), sortedByKey().length);
		Arrays.sort(sortedByValue, (KeyAndValue<V> o1, KeyAndValue<V> o2) -> {
			return o1.value.compareTo(o2.value);
		});
		return sortedByValue;
	}

	public KeyAndValue<V>[] sortedByTimestamp() {
		KeyAndValue<V>[] sortedByTimestamp = Arrays.copyOf(sortedByKey(), sortedByKey().length);
		Arrays.sort(sortedByTimestamp, (KeyAndValue<V> o1, KeyAndValue<V> o2) -> {
			return o1.getTimestamp()==null? -1 : o1.getTimestamp().compareTo(o2.getTimestamp());
		});
		return sortedByTimestamp;
	}

	public List<Integer> keyBasedIndex() {
		return Arrays.asList(sortedByKey()).stream().map((KeyAndValue<V> kv)->{
			return kv.key;
		}).collect(Collectors.toList());
	}

	public List<Comparable<V>> keyBasedArray() {
		return Arrays.asList(sortedByKey()).stream().map((KeyAndValue<V> kv)->{
			return kv.value;
		}).collect(Collectors.toList());
	}

	public KeyAndValue<V>[] sortedByKey() {
		return sortedByKey;
	}
	
	public PhenoCube<V> setSortedByKey(KeyAndValue<V>[] sortedByKey) {
		this.sortedByKey = sortedByKey;
		return this;
	}

	public int getColumnWidth() {
		return columnWidth;
	}

	public PhenoCube<V> setColumnWidth(int columnWidth) {
		this.columnWidth = columnWidth;
		return this;
	}

	public TreeMap<V, TreeSet<Integer>> getCategoryMap() {
		return categoryMap;
	}

	public void setCategoryMap(TreeMap<V, TreeSet<Integer>> categorySetMap) {
		this.categoryMap = categorySetMap;
	}

	public List<KeyAndValue<V>> getLoadingMap() {
		return loadingMap;
	}

	public List<KeyAndValue<V>> getValuesForKeys(Set<Integer> patientIds) {
		List<KeyAndValue<V>> values = new ArrayList<>();
		int x = 0;
		for(Integer id : patientIds) {
			while(x < sortedByKey.length && sortedByKey[x].key<id) {
				x++;
			}
			while(x < sortedByKey.length && sortedByKey[x].key==id) {
				values.add(sortedByKey[x]);
				x++;
			}
		}
		return values;
	}

}
