package edu.harvard.hms.dbmi.avillach.hpds.etl.genotype;

import java.io.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import edu.harvard.hms.dbmi.avillach.hpds.data.genotype.InfoStore;

public class VCFPerPatientInfoStoreSplitter {
	private static Logger logger = LoggerFactory.getLogger(NewVCFLoader.class);
//	private static File mergedFolder = new File("/opt/local/hpds/merged");

	public static void splitAll(File javabinDir, File mergedFolder) throws ClassNotFoundException, FileNotFoundException, IOException,
			InterruptedException, ExecutionException {
		ConcurrentHashMap<String, File> mergedFiles = new ConcurrentHashMap<String, File>();

		File[] inputFiles = javabinDir.listFiles((file) -> {
			return file.getName().endsWith("_infoStores.javabin");
		});

		mergedFolder.mkdir();

		for (File file : inputFiles) {
			try (FileInputStream fisi = new FileInputStream(file);
					GZIPInputStream gzisi = new GZIPInputStream(fisi);
					ObjectInputStream objectInputStream = new ObjectInputStream(gzisi);) {
				ConcurrentHashMap<String, InfoStore> store = (ConcurrentHashMap<String, InfoStore>) objectInputStream
						.readObject();
				logger.info("Loaded " + file.getName());
				store.keySet().parallelStream().forEach((key) -> {
					if (key.contentEquals("PU") || key.contentEquals("IPU")) {

					} else {
						try {
							InfoStore partitionInfoStore = store.get(key);
							File mergedStoreFile;
							if (mergedFiles.containsKey(key)) {
								mergedStoreFile = mergedFiles.get(key);
								InfoStore mergedInfoStore = loadMergedStore(mergedFiles, key);
								for (String value : partitionInfoStore.allValues.keySet()) {
									if (mergedInfoStore.allValues.containsKey(value)) {
										mergedInfoStore.allValues.get(value)
												.addAll(partitionInfoStore.allValues.get(value));
									} else {
										mergedInfoStore.allValues.put(value, partitionInfoStore.allValues.get(value));
									}
								}
								writeStore(mergedFiles, key, mergedInfoStore);
								logger.info("Merged " + key + " for partition " + file.getName());
							} else {
								mergedStoreFile = new File(mergedFolder, key + "_infoStore.javabin");
								mergedFiles.put(key, mergedStoreFile);
								writeStore(mergedFiles, key, partitionInfoStore);
								logger.info("Adding new store to merged set  : " + key);
							}
						} catch (IOException e) {
							logger.error("Error processing file [" + file.getName() + "], key [" + key + "]", e);
						} catch (ClassNotFoundException e) {
							logger.error("Error processing file [" + file.getName() + "], key [" + key + "]", e);
						}
					}

				});
			} catch (FileNotFoundException e) {
				logger.error("Error processing file [" + file.getName() + "] : infoStores.javabin file not found", e);
			} catch (IOException e) {
				logger.error("Error processing file [" + file.getName() + "]", e);
			} catch (ClassNotFoundException e) {
				logger.error("Error processing file [" + file.getName() + "]", e);
			}
		}

	}

	private static InfoStore loadMergedStore(ConcurrentHashMap<String, File> mergedFiles, String key)
			throws FileNotFoundException, IOException, ClassNotFoundException {
		FileInputStream fis = new FileInputStream(mergedFiles.get(key));
		GZIPInputStream gzis = new GZIPInputStream(fis);
		ObjectInputStream ois = new ObjectInputStream(gzis);
		InfoStore mergedInfoStore = (InfoStore) ois.readObject();
		ois.close();
		gzis.close();
		fis.close();
		return mergedInfoStore;
	}

	private static synchronized void writeStore(ConcurrentHashMap<String, File> mergedFiles, String key,
			InfoStore mergedInfoStore) throws FileNotFoundException, IOException {
		FileOutputStream fos = new FileOutputStream(mergedFiles.get(key));
		GZIPOutputStream gzos = new GZIPOutputStream(fos);
		ObjectOutputStream oos = new ObjectOutputStream(gzos);
		oos.writeObject(mergedInfoStore);
		oos.flush();
		oos.close();
		gzos.flush();
		gzos.close();
		fos.flush();
		fos.close();
	}

}
