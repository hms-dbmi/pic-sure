package edu.harvard.hms.dbmi.avillach.hpds.data.genotype;

import java.io.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.stream.Collectors;
import java.util.zip.GZIPOutputStream;

import edu.harvard.hms.dbmi.avillach.hpds.data.storage.FileBackedStorageVariantIndexImpl;
import edu.harvard.hms.dbmi.avillach.hpds.storage.FileBackedByteIndexedStorage;
import edu.harvard.hms.dbmi.avillach.hpds.storage.FileBackedJavaIndexedStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class FileBackedByteIndexedInfoStore implements Serializable {

	private static final long serialVersionUID = 6478256007934827195L;
	private static final Logger log = LoggerFactory.getLogger(FileBackedByteIndexedInfoStore.class);
	public final String column_key;
	public final String description;
	public boolean isContinuous;
	public Float min = Float.MAX_VALUE, max = Float.MIN_VALUE;

	private FileBackedByteIndexedStorage<String, Integer[]> allValues;
	public TreeMap<Double, TreeSet<String>> continuousValueMap;

	public CompressedIndex continuousValueIndex;

	public FileBackedByteIndexedStorage<String, Integer[]> getAllValues() {
		return allValues;
	}

	public List<String> search(String term) {
		if(isContinuous) {
			return new ArrayList<String>();
		}else {
			return allValues.keys().stream().filter((value)->{
				String lowerTerm = term.toLowerCase(Locale.ENGLISH);
				return value.toLowerCase(Locale.ENGLISH).contains(lowerTerm);
			}).collect(Collectors.toList());
		}
	}

	public void addEntry(String value, Integer[] variantIds) throws IOException {
		allValues.put(value, variantIds);
	}


	public void complete() {
		this.allValues.complete();
	}

	public FileBackedByteIndexedInfoStore(File storageFolder, InfoStore infoStore) throws IOException {
		this.allValues = new FileBackedStorageVariantIndexImpl(new File(storageFolder, infoStore.column_key + "_infoStoreStorage.javabin"));
		this.description = infoStore.description;
		this.column_key = infoStore.column_key;
		this.isContinuous = infoStore.isNumeric();
		this.allValues.open();
		if(isContinuous) {
			normalizeNumericStore(infoStore);
		}
		TreeSet<String> sortedKeys = new TreeSet<String>(infoStore.allValues.keySet());
		log.debug(infoStore.column_key + " : " + sortedKeys.size() + " values");
		int x = 0;
		for(String key : sortedKeys){
			if(key.contentEquals(".")) {
				log.debug("Skipping . value for " + infoStore.column_key);
			}else {
				if(x%10000 == 0) {
					log.debug(infoStore.column_key + " " + ((((double)x) / sortedKeys.size()) * 100) + "% done");
				}
				ConcurrentSkipListSet<Integer> variantIds = infoStore.allValues.get(key);
				addEntry(key, variantIds.toArray(new Integer[variantIds.size()]));
				x++;				
			}
		}
		this.allValues.complete();
		if(isContinuous) {
			log.debug(this.column_key + " is continuous, building continuousValueIndex and nulling continuousValueMap.");
			this.continuousValueIndex = new CompressedIndex();
			TreeMap<Float, TreeSet<String>> continuousValueMap = this.continuousValueIndex.buildContinuousValuesMap(this.allValues);
			this.continuousValueIndex.buildIndex(continuousValueMap);
			this.continuousValueMap = null;
		}
	}

	private static void normalizeNumericStore(InfoStore store) {
		TreeSet<String> allKeys = new TreeSet<String>(store.allValues.keySet());

		ConcurrentHashMap<String, ConcurrentSkipListSet<Integer>> normalizedValues = new ConcurrentHashMap<>();
		for(String key : allKeys) {
			String[] keys = key.split(",");
			ConcurrentSkipListSet<Integer> variantIds = store.allValues.get(key);
			if(key.contentEquals(".")) {
				//don't add it
			}else if(keyHasMultipleValues(keys)) {
				for(String value : keys) {
					if(value.contentEquals(".")) {

					}else {
						ConcurrentSkipListSet<Integer> normalizedVariantIds = normalizedValues.get(value);
						if(normalizedVariantIds == null) {
							normalizedVariantIds = variantIds;
						}else {
							normalizedVariantIds.addAll(variantIds);
						}
						normalizedValues.put(value, normalizedVariantIds);
					}
				}
			}else {
				if(key.contentEquals(".")) {

				}else {
					ConcurrentSkipListSet<Integer> normalizedVariantIds = normalizedValues.get(key);
					if(normalizedVariantIds == null) {
						normalizedVariantIds = variantIds;
					}else {
						normalizedVariantIds.addAll(variantIds);
					}
					normalizedValues.put(key, normalizedVariantIds);
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

	public void updateStorageDirectory(File storageDirectory) {
		allValues.updateStorageDirectory(storageDirectory);
	}

	public void write(File outputFile) {
		try(
				FileOutputStream fos = new FileOutputStream(outputFile);
				GZIPOutputStream gzos = new GZIPOutputStream(fos);
				ObjectOutputStream oos = new ObjectOutputStream(gzos);) {
			oos.writeObject(this);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}
}

