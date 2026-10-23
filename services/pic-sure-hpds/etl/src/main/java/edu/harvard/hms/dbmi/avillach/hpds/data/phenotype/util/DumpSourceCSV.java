package edu.harvard.hms.dbmi.avillach.hpds.data.phenotype.util;

import java.io.*;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
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
import edu.harvard.hms.dbmi.avillach.hpds.etl.LoadingStore;

/**
 * Writes every observation of a store as plaintext CSV to {@code allConcepts.csv} in the store's directory, {@code /opt/local/hpds}
 * when run as a program.
 *
 * <p>The output holds every observation in the clear, so it is created owner-only (mode {@code 0600}), an existing file is never
 * overwritten, nothing is created when the encryption key cannot be loaded, and a dump that fails part way is deleted so a later run
 * is not refused. It is handled like the store itself.
 */
public class DumpSourceCSV {
	protected static LoadingCache<String, PhenoCube<?>> store;

	protected static TreeMap<String, ColumnMeta> metaStoreSource;

	protected static TreeSet<Integer> allIds;

	private static final int PATIENT_NUM = 0;

	private static final int CONCEPT_PATH = 1;

	private static final int NUMERIC_VALUE = 2;

	private static final int TEXT_VALUE = 3;
	
	private static final int TIMESTAMP = 4;

	/** The name of the dump file written into the store directory. */
	static final String OUTPUT_FILE = "allConcepts.csv";

	public static void main(String[] args) throws ClassNotFoundException, FileNotFoundException, IOException {
		dump(Path.of("/opt/local/hpds"));
	}

	/**
	 * Dumps the store in {@code directory} to {@link #OUTPUT_FILE} in the same directory.
	 *
	 * @param directory the store directory, holding {@code encryption_key}, {@code columnMeta.javabin} and
	 *        {@code allObservationsStore.javabin}
	 * @throws IOException when the store cannot be read or the output cannot be written
	 * @throws IllegalStateException when no encryption key could be loaded, before anything is created
	 */
	static void dump(Path directory) throws ClassNotFoundException, FileNotFoundException, IOException {
		Path keyFile = directory.resolve("encryption_key");
		if (!Files.isReadable(keyFile)) {
			throw new IllegalStateException("no encryption key at " + keyFile + "; nothing was written");
		}
		Crypto.loadKey(Crypto.DEFAULT_KEY_NAME, keyFile.toString());
		if (!Crypto.hasKey(Crypto.DEFAULT_KEY_NAME)) {
			throw new IllegalStateException("no encryption key could be loaded; nothing was written");
		}
		Object[] metadata = loadMetadata(directory);
		metaStoreSource = (TreeMap<String, ColumnMeta>) metadata[0];
		allIds = (TreeSet<Integer>) metadata[1];
		store = initializeCache(directory); 
		Path output = directory.resolve(OUTPUT_FILE);
		FileChannel channel;
		try {
			channel = ExportForMigration.createPrivateFile(output);
		} catch (FileAlreadyExistsException e) {
			FileAlreadyExistsException refused = new FileAlreadyExistsException(output.toString(), null, "an earlier dump is there; move or delete it to dump again");
			refused.initCause(e);
			throw refused;
		}
		try (FileChannel file = channel; CSVPrinter writer = CSVFormat.DEFAULT.print(new OutputStreamWriter(Channels.newOutputStream(file), StandardCharsets.UTF_8))) {
			writeRows(writer);
		} catch (Throwable failure) {
			deleteAfter(output, failure);
			throw failure;
		}
	}

	private static void writeRows(CSVPrinter writer) throws IOException {
		writer.printRecord(ImmutableList.of(new String[] {"PATIENT_NUM","CONCEPT_PATH","NUMERIC_VALUE","TEXT_VALUE","TIMESTAMP"}));
		metaStoreSource.keySet().forEach((String key)->{
			try {
				PhenoCube cube = store.get(key);
				ArrayList<String[]> cubeLines = new ArrayList<>();
				for(KeyAndValue kv : cube.sortedByKey()) {
					String[] line = new String[5];
					line[PATIENT_NUM] = kv.getKey().toString();
					line[CONCEPT_PATH] = key;
					line[NUMERIC_VALUE] = cube.isStringType() ? "" : kv.getValue().toString();
					line[TEXT_VALUE] = cube.isStringType() ? kv.getValue().toString() : "";
					line[TIMESTAMP] = kv.getTimestamp() == null ? null : kv.getTimestamp().toString();
					cubeLines.add(line);
				}
				writer.printRecords(cubeLines);
			} catch(ExecutionException e) {
				throw new RuntimeException(e);
			} catch (IOException e) {
				throw new UncheckedIOException(e);
			}
		});
		writer.flush();
	}

	private static void deleteAfter(Path output, Throwable failure) {
		try {
			Files.deleteIfExists(output);
		} catch (IOException deleteFailure) {
			failure.addSuppressed(deleteFailure);
		}
	}
	
	static LoadingStore loadingStoreSource = new LoadingStore();
	
	protected static Object[] loadMetadata(Path directory) {
		try (ObjectInputStream objectInputStream = new ObjectInputStream(new GZIPInputStream(new FileInputStream(directory.resolve("columnMeta.javabin").toFile())));){
			TreeMap<String, ColumnMeta> metastore = (TreeMap<String, ColumnMeta>) objectInputStream.readObject();
			TreeMap<String, ColumnMeta> metastoreScrubbed = new TreeMap<String, ColumnMeta>();
			for(Entry<String,ColumnMeta> entry : metastore.entrySet()) {
				metastoreScrubbed.put(entry.getKey().replaceAll("\\ufffd",""), entry.getValue());
			}
			Set<Integer> allIds = (TreeSet<Integer>) objectInputStream.readObject();
			return new Object[] {metastoreScrubbed, allIds};
		} catch (IOException | ClassNotFoundException e) {
			throw new RuntimeException("Could not load metastore");
		} 
	}
	
	protected static LoadingCache<String, PhenoCube<?>> initializeCache(Path directory) throws ClassNotFoundException, FileNotFoundException, IOException {
		return CacheBuilder.newBuilder()
				.maximumSize(100)
				.build(
						new CacheLoader<String, PhenoCube<?>>() {
							public PhenoCube<?> load(String key) throws Exception {
								try(RandomAccessFile allObservationsStore = new RandomAccessFile(directory.resolve("allObservationsStore.javabin").toFile(), "r");){
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
