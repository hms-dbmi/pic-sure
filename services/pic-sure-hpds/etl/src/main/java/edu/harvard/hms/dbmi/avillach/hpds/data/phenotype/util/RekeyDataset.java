package edu.harvard.hms.dbmi.avillach.hpds.data.phenotype.util;

import java.io.*;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.Map.Entry;
import java.util.concurrent.ExecutionException;
import java.util.zip.GZIPInputStream;
import org.apache.log4j.Logger;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;

import edu.harvard.hms.dbmi.avillach.hpds.crypto.Crypto;
import edu.harvard.hms.dbmi.avillach.hpds.data.phenotype.ColumnMeta;
import edu.harvard.hms.dbmi.avillach.hpds.data.phenotype.PhenoCube;
import edu.harvard.hms.dbmi.avillach.hpds.etl.phenotype.LoadingStore;

@SuppressWarnings({"unchecked"})
public class RekeyDataset {

	private static final String SOURCE = "SOURCE";

	private static LoadingStore store = new LoadingStore();

	private static Logger log = Logger.getLogger(RekeyDataset.class); 

	protected static LoadingCache<String, PhenoCube<?>> sourceStore;

	protected static TreeMap<String, ColumnMeta> sourceMetaStore;

	public static void main(String[] args) throws IOException, ClassNotFoundException, ExecutionException {
		Crypto.loadDefaultKey();
		Crypto.loadKey(SOURCE, "/opt/local/source/encryption_key");
		sourceStore = initializeCache(); 
		Object[] metadata = loadMetadata();
		sourceMetaStore = (TreeMap<String, ColumnMeta>) metadata[0];
		store.allObservationsStore = new RandomAccessFile("/opt/local/hpds/allObservationsStore.javabin", "rw");
		initialLoad();
		store.saveStore();
	}

	private static void initialLoad() throws IOException, ExecutionException {
		for(String conceptPath : sourceMetaStore.keySet()) {
			PhenoCube<?> cube = sourceStore.get(conceptPath);
			store.store.put(conceptPath, cube);
		}
	}

	/**
	 * Build the PhenoCube cache.
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
										ObjectInputStream inStream = new ObjectInputStream(new ByteArrayInputStream(Crypto.decryptData(SOURCE, buffer)));
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
