package edu.harvard.hms.dbmi.avillach.hpds.etl.genotype;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import edu.harvard.hms.dbmi.avillach.hpds.data.genotype.InfoStore;

public class VCFPerPatientInfoStoreSplitter {
	static File mergedFolder = new File("/opt/local/hpds/merged");
	public static void splitAll() throws ClassNotFoundException, FileNotFoundException, IOException, InterruptedException, ExecutionException {
		ConcurrentHashMap<String, File> mergedFiles = new ConcurrentHashMap<String, File>();

		File[] inputFiles = new File("/opt/local/hpds/all/").listFiles((file)->{return file.getName().endsWith("_infoStores.javabin");});

		mergedFolder.mkdir();

		for(File file : inputFiles) {
			try (
					FileInputStream fisi = new FileInputStream(file);
					GZIPInputStream gzisi = new GZIPInputStream(fisi);
					ObjectInputStream objectInputStream = new ObjectInputStream(gzisi);
					){
				ConcurrentHashMap<String, InfoStore> store = (ConcurrentHashMap<String, InfoStore>) objectInputStream.readObject();
				System.out.println("loaded " + file.getName());
				store.keySet().parallelStream().forEach((key)->{
					if(key.contentEquals("PU") || key.contentEquals("IPU")) {

					}else {
						try{
							InfoStore partitionInfoStore = store.get(key);
							File mergedStoreFile;
							if(mergedFiles.containsKey(key)) {
								mergedStoreFile = mergedFiles.get(key);
								InfoStore mergedInfoStore = loadMergedStore(mergedFiles, key);
								for(String value : partitionInfoStore.allValues.keySet()) {
									if(mergedInfoStore.allValues.containsKey(value)) {
										mergedInfoStore.allValues.get(value).addAll(partitionInfoStore.allValues.get(value));
									}else {
										mergedInfoStore.allValues.put(value, partitionInfoStore.allValues.get(value));
									}
								}
								writeStore(mergedFiles, key, mergedInfoStore);
								System.out.println("Merged " + key + " for partition " + file.getName());
							} else {
								mergedStoreFile = new File(mergedFolder, key + "_infoStore.javabin");
								mergedFiles.put(key, mergedStoreFile);
								writeStore(mergedFiles, key, partitionInfoStore);
								System.out.println("Adding new store to merged set  : " + key);
							}
						} catch (IOException e) {
							// TODO Auto-generated catch block
							e.printStackTrace();
						} catch (ClassNotFoundException e) {
							// TODO Auto-generated catch block
							e.printStackTrace();
						}
					}

				});
			}catch(FileNotFoundException e) {
				System.out.println("infoStores.javabin file not found");
				e.printStackTrace();
			} catch (IOException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			} catch (ClassNotFoundException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
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

	private static synchronized void writeStore(ConcurrentHashMap<String, File> mergedFiles, String key, InfoStore mergedInfoStore)
			throws FileNotFoundException, IOException {
		FileOutputStream fos = new FileOutputStream(mergedFiles.get(key));
		GZIPOutputStream gzos = new GZIPOutputStream(fos);
		ObjectOutputStream oos = new ObjectOutputStream(gzos);
		oos.writeObject(mergedInfoStore);
		oos.flush();oos.close();
		gzos.flush(); gzos.close();
		fos.flush();fos.close();
	}

}
