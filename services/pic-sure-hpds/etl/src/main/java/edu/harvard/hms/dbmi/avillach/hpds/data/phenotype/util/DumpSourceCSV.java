package edu.harvard.hms.dbmi.avillach.hpds.data.phenotype.util;

import java.io.ByteArrayInputStream;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileWriter;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.Map.Entry;
import java.util.concurrent.ExecutionException;
import java.util.zip.GZIPInputStream;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.ImmutableList;

import edu.harvard.hms.dbmi.avillach.hpds.crypto.Crypto;
import edu.harvard.hms.dbmi.avillach.hpds.data.phenotype.ColumnMeta;
import edu.harvard.hms.dbmi.avillach.hpds.data.phenotype.KeyAndValue;
import edu.harvard.hms.dbmi.avillach.hpds.data.phenotype.PhenoCube;
import edu.harvard.hms.dbmi.avillach.hpds.etl.phenotype.LoadingStore;

public class DumpSourceCSV {
	protected static LoadingCache<String, PhenoCube<?>> store;

	protected static TreeMap<String, ColumnMeta> metaStoreSource;

	protected static TreeSet<Integer> allIds;

	private static final int PATIENT_NUM = 0;

	private static final int CONCEPT_PATH = 1;

	private static final int NUMERIC_VALUE = 2;

	private static final int TEXT_VALUE = 3;
	
	public static void main(String[] args) throws ClassNotFoundException, FileNotFoundException, IOException {
		Object[] metadata = loadMetadata();
		metaStoreSource = (TreeMap<String, ColumnMeta>) metadata[0];
		allIds = (TreeSet<Integer>) metadata[1];
		store = initializeCache(); 
		CSVPrinter writer = CSVFormat.DEFAULT.printer();
		FileWriter fWriter = new FileWriter("/opt/local/hpds/allConcepts.csv");
		writer.printRecord(ImmutableList.of(new String[] {"PATIENT_NUM","CONCEPT_PATH","NUMERIC_VALUE","TEXT_VALUE"}));
		metaStoreSource.keySet().forEach((String key)->{
			try {
				PhenoCube cube = store.get(key);
				ArrayList<String[]> cubeLines = new ArrayList<>();
				for(KeyAndValue kv : cube.sortedByKey()) {
					String[] line = new String[4];
					line[PATIENT_NUM] = kv.getKey().toString();
					line[CONCEPT_PATH] = key;
					line[NUMERIC_VALUE] = cube.isStringType() ? "" : kv.getValue().toString();
					line[TEXT_VALUE] = cube.isStringType() ? kv.getValue().toString() : "";
					cubeLines.add(line);
				}
				writer.printRecords(cubeLines);
			}catch(ExecutionException e) {
				e.printStackTrace();
			} catch (IOException e) {
				e.printStackTrace();
			}
		});
	}
	
	static LoadingStore loadingStoreSource = new LoadingStore();
	
	protected static Object[] loadMetadata() {
		try (ObjectInputStream objectInputStream = new ObjectInputStream(new GZIPInputStream(new FileInputStream("/opt/local/hpds/columnMeta.javabin")));){
			TreeMap<String, ColumnMeta> metastore = (TreeMap<String, ColumnMeta>) objectInputStream.readObject();
			TreeMap<String, ColumnMeta> metastoreScrubbed = new TreeMap<String, ColumnMeta>();
			for(Entry<String,ColumnMeta> entry : metastore.entrySet()) {
				metastoreScrubbed.put(entry.getKey().replaceAll("\\ufffd",""), entry.getValue());
			}
			Set<Integer> allIds = (TreeSet<Integer>) objectInputStream.readObject();
			return new Object[] {metastoreScrubbed, allIds};
		} catch (IOException | ClassNotFoundException e) {
			e.printStackTrace();
			throw new RuntimeException("Could not load metastore");
		} 
	}
	
	protected static LoadingCache<String, PhenoCube<?>> initializeCache() throws ClassNotFoundException, FileNotFoundException, IOException {
		return CacheBuilder.newBuilder()
				.maximumSize(100)
				.build(
						new CacheLoader<String, PhenoCube<?>>() {
							public PhenoCube<?> load(String key) throws Exception {
								try(RandomAccessFile allObservationsStore = new RandomAccessFile("/opt/local/hpds/allObservationsStore.javabin", "r");){
									ColumnMeta columnMeta = metaStoreSource.get(key);
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
}
