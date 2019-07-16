package edu.harvard.hms.dbmi.avillach.hpds.data.genotype;

import java.io.File;
import java.io.IOException;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.stream.Collectors;

import edu.harvard.hms.dbmi.avillach.hpds.storage.FileBackedByteIndexedStorage;

public class FileBackedByteIndexedInfoStore implements Serializable {

	private static final long serialVersionUID = 6478256007934827195L;
	public final String column_key;
	public final String description;
	public boolean isContinuous;
	public Double min = Double.MAX_VALUE, max = Double.MIN_VALUE;

	public FileBackedByteIndexedStorage<String, String[]> allValues;
	public TreeMap<Double, TreeSet<String>> continuousValueMap;

	public CompressedIndex continuousValueIndex;

	public List<String> search(String term) {
		if(isContinuous) {
			return new ArrayList<String>();
		}else {
			return allValues.keys().stream().filter((value)->{
				String lowerTerm = term.toLowerCase();
				return value.toLowerCase().contains(lowerTerm);
			}).collect(Collectors.toList());
		}
	}

	public void addEntry(String value, String[] variantSpecs) throws IOException {
		allValues.put(value, variantSpecs);
	}


	public void complete() {
		this.allValues.complete();
	}

	public FileBackedByteIndexedInfoStore(File storageFolder, InfoStore infoStore) throws IOException {
		this.allValues = new FileBackedByteIndexedStorage(String.class, String[].class, 
				new File(storageFolder, infoStore.column_key + "_infoStoreStorage.javabin"));
		this.description = infoStore.description;
		this.column_key = infoStore.column_key;
		this.isContinuous = infoStore.isNumeric();
		this.allValues.open();
		if(isContinuous) {
			normalizeNumericStore(infoStore);
		}
		System.out.println("" + infoStore.allValues.values().stream().collect(Collectors.summingInt((value)->{return value.size();})));
		TreeSet<String> sortedKeys = new TreeSet<String>(infoStore.allValues.keySet());
		System.out.println(infoStore.column_key + " : " + sortedKeys.size() + " values");
		int x = 0;
		for(String key : sortedKeys){
			if(key.contentEquals(".")) {
				System.out.println("Skipping . value for " + infoStore.column_key);
			}else {
				if(x%10000 == 0) {
					System.out.println(infoStore.column_key + " " + ((((double)x) / sortedKeys.size()) * 100) + "% done");
				}
				ConcurrentSkipListSet<String> variantSpecs = infoStore.allValues.get(key);
				addEntry(key, variantSpecs.toArray(new String[variantSpecs.size()]));
				x++;				
			}
		}
		this.allValues.complete();
		if(isContinuous) {
			System.out.println(this.column_key + " is continuous, building continuousValueIndex and nulling continuousValueMap.");
			this.continuousValueIndex = new CompressedIndex();
			TreeMap<Float, TreeSet<String>> continuousValueMap = this.continuousValueIndex.buildContinuousValuesMap(this.allValues);
			this.continuousValueIndex.buildIndex(continuousValueMap);
			this.continuousValueMap = null;
		}
	}

	private static void normalizeNumericStore(InfoStore store) {
		TreeSet<String> allKeys = new TreeSet<String>(store.allValues.keySet());

		ConcurrentHashMap<String, ConcurrentSkipListSet<String>> normalizedValues = new ConcurrentHashMap<>();
		for(String key : allKeys) {
			String[] keys = key.split(",");
			ConcurrentSkipListSet<String> variantSpecs = store.allValues.get(key);
			if(key.contentEquals(".")) {
				//don't add it
			}else if(keyHasMultipleValues(keys)) {
				for(String value : keys) {
					if(value.contentEquals(".")) {

					}else {
						ConcurrentSkipListSet<String> normalizedSpecs = normalizedValues.get(value);
						if(normalizedSpecs == null) {
							normalizedSpecs = variantSpecs;
						}else {
							normalizedSpecs.addAll(variantSpecs);
						}
						normalizedValues.put(value, normalizedSpecs);					
					}
				}
			}else {
				if(key.contentEquals(".")) {

				}else {
					ConcurrentSkipListSet<String> normalizedSpecs = normalizedValues.get(key);
					if(normalizedSpecs == null) {
						normalizedSpecs = variantSpecs;
					}else {
						normalizedSpecs.addAll(variantSpecs);
					}
					normalizedValues.put(key, normalizedSpecs);					
				}
			}

		}
		store.allValues = normalizedValues;
	}

	private static boolean keyHasMultipleValues(String[] keys) {
		int x = 0;
		for(String k : keys) {
			if(k == null || k.isEmpty()) {

			}else {
				x++;
			}
		}
		return x>1;
	}

}

