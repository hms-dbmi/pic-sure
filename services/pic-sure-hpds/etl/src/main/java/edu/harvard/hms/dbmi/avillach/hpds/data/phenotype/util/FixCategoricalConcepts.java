package edu.harvard.hms.dbmi.avillach.hpds.data.phenotype.util;

import java.io.*;
import java.util.*;
import java.util.Map.Entry;
import java.util.concurrent.ExecutionException;
import java.util.zip.GZIPInputStream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.CacheLoader.InvalidCacheLoadException;
import com.google.common.cache.LoadingCache;

import edu.harvard.hms.dbmi.avillach.hpds.crypto.Crypto;
import edu.harvard.hms.dbmi.avillach.hpds.data.phenotype.ColumnMeta;
import edu.harvard.hms.dbmi.avillach.hpds.data.phenotype.KeyAndValue;
import edu.harvard.hms.dbmi.avillach.hpds.data.phenotype.PhenoCube;
import edu.harvard.hms.dbmi.avillach.hpds.etl.phenotype.LoadingStore;

@SuppressWarnings({"unchecked", "rawtypes"})
public class FixCategoricalConcepts {
	
	private static LoadingStore store = new LoadingStore();

	private static Logger log = LoggerFactory.getLogger(FixCategoricalConcepts.class); 

	protected static LoadingCache<String, PhenoCube<?>> sourceStore;

	protected static TreeMap<String, ColumnMeta> sourceMetaStore;

	private static final int PATIENT_NUM = 0;

	private static final int CONCEPT_PATH = 1;

	private static final int NUMERIC_VALUE = 2;

	private static final int TEXT_VALUE = 3;

	private static final int TIMESTAMP = 4;

	public static void main(String[] args) throws IOException, ClassNotFoundException, ExecutionException {
		sourceStore = initializeCache(); 
		Object[] metadata = loadMetadata();
		sourceMetaStore = (TreeMap<String, ColumnMeta>) metadata[0];
		store.allObservationsStore = new RandomAccessFile("/opt/local/hpds/allObservationsStore.javabin", "rw");
		initialLoad();
		store.saveStore();
	}

	private static void initialLoad() throws IOException, ExecutionException {
		final PhenoCube[] currentConcept = new PhenoCube[1];
		Map<String, List<String>> conceptsToMerge = new TreeMap<String, List<String>>();
		for(String conceptPath : sourceMetaStore.keySet()) {
			if(conceptPath.endsWith("/")) {
				//this is a potentially bugged categorical concept
				String correctConcept = conceptPath.substring(0, conceptPath.lastIndexOf("\\"));
				List<String> sisterConcepts = conceptsToMerge.get(correctConcept);
				if(sisterConcepts == null) {
					sisterConcepts = new ArrayList<String>();
					conceptsToMerge.put(correctConcept, sisterConcepts);
				}
				sisterConcepts.add(conceptPath);
			}else {
				conceptsToMerge.put(conceptPath, List.of(conceptPath));
			}
		}
		for(String conceptPath : conceptsToMerge.keySet()) {
			for(String concept : conceptsToMerge.get(conceptPath)) {
				PhenoCube cubeForPath = sourceStore.get(concept);
				for(KeyAndValue entry : cubeForPath.sortedByKey()) {
					processRecord(currentConcept, List.of(
							entry.getKey().toString(),
							conceptPath,
							cubeForPath.isStringType()?"":entry.getValue().toString(), 
							cubeForPath.isStringType()?entry.getValue().toString():""));
				}
			}
		}
	}

	private static void processRecord(final PhenoCube[] currentConcept, List<String> record) {
		try {
			String conceptPathFromRow = record.get(CONCEPT_PATH);
			String[] segments = conceptPathFromRow.split("\\\\");
			for(int x = 0;x<segments.length;x++) {
				segments[x] = segments[x].trim();
			}
			conceptPathFromRow = String.join("\\", segments) + "\\";
			conceptPathFromRow = conceptPathFromRow.replaceAll("\\ufffd", "");
			String textValueFromRow = record.get(TEXT_VALUE) == null ? null : record.get(TEXT_VALUE).trim();
			if(textValueFromRow!=null) {
				textValueFromRow = textValueFromRow.replaceAll("\\ufffd", "");
			}
			String conceptPath = conceptPathFromRow.endsWith("\\" +textValueFromRow+"\\") ? conceptPathFromRow.replaceAll("\\\\[^\\\\]*\\\\$", "\\\\") : conceptPathFromRow;
			// This is not getDouble because we need to handle null values, not coerce them into 0s
			String numericValue = record.get(NUMERIC_VALUE);
			if((numericValue==null || numericValue.isEmpty()) && textValueFromRow!=null) {
				try {
					numericValue = Double.parseDouble(textValueFromRow) + "";
				}catch(NumberFormatException e) {
					
				}
			}
			boolean isAlpha = (numericValue == null || numericValue.isEmpty());
			if(currentConcept[0] == null || !currentConcept[0].name.equals(conceptPath)) {
				System.out.println(conceptPath);
				try {
					currentConcept[0] = store.store.get(conceptPath);
				} catch(InvalidCacheLoadException e) {
					currentConcept[0] = new PhenoCube(conceptPath, isAlpha ? String.class : Double.class);
					store.store.put(conceptPath, currentConcept[0]);
				}
			}
			String value = isAlpha ? record.get(TEXT_VALUE) : numericValue;

			if(value != null && !value.trim().isEmpty() && ((isAlpha && currentConcept[0].vType == String.class)||(!isAlpha && currentConcept[0].vType == Double.class))) {
				value = value.trim();
				currentConcept[0].setColumnWidth(isAlpha ? Math.max(currentConcept[0].getColumnWidth(), value.getBytes().length) : Double.BYTES);
				currentConcept[0].add(Integer.parseInt(record.get(PATIENT_NUM)), isAlpha ? value : Double.parseDouble(value), new Date(record.get(TIMESTAMP)));
				store.allIds.add(Integer.parseInt(record.get(PATIENT_NUM)));
			}
		} catch (ExecutionException e) {
			e.printStackTrace();
		}
	}

	/**
	 * Load the variantStore object from disk and build the PhenoCube cache.
	 * 
	 * @return
	 * @throws ClassNotFoundException
	 * @throws FileNotFoundException
	 * @throws IOException
	 */
	protected static LoadingCache<String, PhenoCube<?>> initializeCache() throws ClassNotFoundException, FileNotFoundException, IOException {
		return CacheBuilder.newBuilder()
				.maximumSize(10)
				.build(
						new CacheLoader<String, PhenoCube<?>>() {
							public PhenoCube<?> load(String key) throws Exception {
								try(RandomAccessFile allObservationsStore = new RandomAccessFile("/opt/local/source/allObservationsStore.javabin", "r");){
									ColumnMeta columnMeta = sourceMetaStore.get(key);
									if(columnMeta != null) {
										allObservationsStore.seek(columnMeta.getAllObservationsOffset());
										int length = (int) (columnMeta.getAllObservationsLength() - columnMeta.getAllObservationsOffset());
										byte[] buffer = new byte[length];
										allObservationsStore.read(buffer);
										allObservationsStore.close();
										ObjectInputStream inStream = new ObjectInputStream(new ByteArrayInputStream(Crypto.decryptData(buffer)));
										PhenoCube<?> ret = (PhenoCube<?>)inStream.readObject();
										inStream.close();
										return ret;																		
									}else {
										System.out.println("ColumnMeta not found for : [" + key + "]");
										return null;
									}
								}
							}
						});
	}

	protected static Object[] loadMetadata() {
		try (ObjectInputStream objectInputStream = new ObjectInputStream(new GZIPInputStream(new FileInputStream("/opt/local/source/columnMeta.javabin")));){
			TreeMap<String, ColumnMeta> metastore = (TreeMap<String, ColumnMeta>) objectInputStream.readObject();
			TreeMap<String, ColumnMeta> metastoreScrubbed = new TreeMap<String, ColumnMeta>();
			for(Entry<String,ColumnMeta> entry : metastore.entrySet()) {
				metastoreScrubbed.put(entry.getKey().replaceAll("\\ufffd",""), entry.getValue());
			}
			Set<Integer> allIds = (TreeSet<Integer>) objectInputStream.readObject();
			return new Object[] {metastoreScrubbed, allIds};
		} catch (IOException | ClassNotFoundException e) {
			e.printStackTrace();
			log.warn("************************************************");
			log.warn("************************************************");
			log.warn("Could not load metastore");
			log.warn("If you meant to include phenotype data of any kind, please check that the file /opt/local/source/columnMeta.javabin exists and is readable by the service.");
			log.warn("************************************************");
			log.warn("************************************************");
			return new Object[] {new TreeMap<String, ColumnMeta>(), new TreeSet<Integer>()};
		} 
	}

}
