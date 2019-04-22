package edu.harvard.hms.dbmi.avillach.hpds.etl.genotype;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectOutputStream;
import java.util.Arrays;
import java.util.HashMap;
import java.util.TreeSet;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.zip.GZIPOutputStream;

import edu.harvard.hms.dbmi.avillach.hpds.data.genotype.InfoStore;
import edu.harvard.hms.dbmi.avillach.hpds.data.genotype.VariantMasks;
import edu.harvard.hms.dbmi.avillach.hpds.data.genotype.VariantStore;
import edu.harvard.hms.dbmi.avillach.hpds.storage.FileBackedByteIndexedStorage;


public class VCFLocalLoader {

	public static void main(String[] args) throws FileNotFoundException, IOException {
		File inputDir = new File("/opt/local/hpds/vcfInput/");
		File storageDir = new File("/opt/local/hpds/vcfOutput");
		loadVCFs(inputDir, storageDir);
	}

	static PerPatientVCFLocalProducer[] producers;

	private static void loadVCFs(File inputDir, File storageDir) throws FileNotFoundException, IOException {
		long startTime = System.currentTimeMillis();

		File[] inputFiles = inputDir.listFiles((file)->{
			return file.getName().endsWith(".vcf.gz");
		});

		createAndStartProducers(inputFiles);

		TreeSet<String> patientIds = new TreeSet<>(
				Arrays.stream(producers).map(producer->{return producer.patientId;})
				.collect(Collectors.toList()));

		HashMap<String, Integer> patientIdMap = logPatientIds(patientIds);

		primeQueues();

		ConcurrentHashMap<String, InfoStore> infoStores = new ConcurrentHashMap<String, InfoStore>();
		VariantStore store = new VariantStore();
		store.setPatientIds(patientIds.toArray(new String[0]));
		FileBackedByteIndexedStorage<Integer, ConcurrentHashMap<String, VariantMasks>>[] variantMaskStorage = 
				new FileBackedByteIndexedStorage[24];

		parseHeaders(infoStores);

		VCFLine lastLineProcessed = null;
		int lastChromosomeProcessed = 0;
		int lastChunkProcessed = 0;
		HashMap<String, String[]> loadingMap = new HashMap<>();
		
		/*
		 *  This executor is used to offload the work of converting loading maps to mask maps. 
		 *  During this conversion each 0/1, 0/1, 0/0 etc value for each patient is changed to a
		 *  single bit in the appropriate zygosity bitmask of a VariantMask.
		 */
		ExecutorService ex = Executors.newFixedThreadPool(1);
		do {
			lastLineProcessed = getLowestChromosomalOffsetLine();
			if(lastLineProcessed != null) {
				int currentChunk = lastLineProcessed.offset/1000;
				if(lastLineProcessed.chromosome>lastChromosomeProcessed || currentChunk > lastChunkProcessed) {
					loadingMap = flipChunk(storageDir, infoStores, variantMaskStorage, lastLineProcessed,
							lastChromosomeProcessed, lastChunkProcessed, loadingMap, ex, false);
					lastChromosomeProcessed = lastLineProcessed.chromosome;
					lastChunkProcessed = currentChunk;
					if(lastChunkProcessed % 1000 == 0) {
						System.out.println(System.currentTimeMillis() + " Done loading chunk : " + lastChunkProcessed + " for chromosome " + lastChromosomeProcessed);
					}
				}
				processLine(patientIds, patientIdMap, infoStores, lastLineProcessed, loadingMap);
			}
			Thread.yield();
		} while(lastLineProcessed != null && Arrays.stream(producers).anyMatch(
				producer->{return !producer.vcfLineQueue.isEmpty() || producer.isRunning(); }));
		// Don't forget to save the last chunk.
		loadingMap = flipChunk(storageDir, infoStores, variantMaskStorage, lastLineProcessed,
				lastChromosomeProcessed, lastChunkProcessed, loadingMap, ex, true);
		ex.shutdown();
		while(!ex.isTerminated()) {
			try {
				System.out.println(((ThreadPoolExecutor)ex).getQueue().size() + " writes left to be written.");
				ex.awaitTermination(20, TimeUnit.SECONDS);
			} catch (InterruptedException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}			
		}
		System.out.println("Done processing VCFs.");

		saveVariantStore(storageDir, store, variantMaskStorage);

		saveInfoStores(storageDir, startTime, infoStores);

		splitInfoStoresByColumn(startTime);

		convertInfoStoresToByteIndexed(startTime);
	}

	private static void processLine(TreeSet<String> patientIds, HashMap<String, Integer> patientIdMap,
			ConcurrentHashMap<String, InfoStore> infoStores, VCFLine lastLineProcessed,
			HashMap<String, String[]> loadingMap) {
		String[] variantValues;
		final String specNotation = lastLineProcessed.specNotation();
		variantValues = loadingMap.get(specNotation);
		if(variantValues==null) {
			variantValues = new String[patientIds.size()];
			loadingMap.put(specNotation, variantValues);
		}
		variantValues[patientIdMap.get(producers[0].patientId)] 
				= lastLineProcessed.data.substring(0,3);
		String[] infoValues = (lastLineProcessed.info).split(";");
		infoStores.values().parallelStream().forEach(infoStore->{
			try {
				infoStore.processRecord(specNotation, infoValues);
			}catch(NumberFormatException e) {
				System.out.println("Skipping record due to parsing exception : " + String.join(",", infoValues));
			}
		});
	}

	private static HashMap<String, Integer> logPatientIds(TreeSet<String> patientIds) {
		HashMap<String, Integer> patientIdMap = new HashMap<String, Integer>(patientIds.size());
		int x = 0;
		for(String patientId : patientIds) {
			patientIdMap.put(patientId, x++);
		}

		System.out.println("Listed all patients. " + patientIds.size());
		return patientIdMap;
	}

	private static void createAndStartProducers(File[] inputFiles) {
		producers = new PerPatientVCFLocalProducer[inputFiles.length];
		for(int x = 0;x<inputFiles.length;x++) {
			PerPatientVCFLocalProducer producer = new PerPatientVCFLocalProducer(inputFiles[x], new ArrayBlockingQueue<>(100));
			producer.start();
			producers[x] = producer;
		}

		System.out.println("Started all producers. " + producers.length);
	}

	private static void primeQueues() {
		// wait until all queues are primed
		for(ArrayBlockingQueue<VCFLine> queue : Arrays.stream(producers).map(producer->{return producer.vcfLineQueue;}).collect(Collectors.toList())) {
			while(queue.size() < 100) {
				sleep();
			}
		}

		System.out.println("Producers are primed.");
	}

	private static void parseHeaders(ConcurrentHashMap<String, InfoStore> infoStores) {
		for(PerPatientVCFLocalProducer producer : producers) {
			for(String line : producer.headerLines) {
				if(line.startsWith("##INFO") && ! line.contains("ID=PU,")) {
					String[] info = line.replaceAll("##INFO=<", "").replaceAll(">", "").split(",");
					info[3] = String.join(",", Arrays.copyOfRange(info, 3, info.length));
					String columnKey = info[0].split("=")[1];
					if(! infoStores.containsKey(columnKey)) {
						System.out.println("Creating info store " + line);
						InfoStore infoStore = new InfoStore(info[3], ";", columnKey);
						infoStores.put(columnKey, infoStore);										
					}
				} else {
					System.out.println("Skipping header row : " + line);	
				}
			}
		}

		System.out.println("Header lines parsed.");
	}

	private static VCFLine getLowestChromosomalOffsetLine() {
		VCFLine lastLineProcessed;
		Arrays.sort(producers, (a, b)->{
			VCFLine aLine = a.vcfLineQueue.peek();
			VCFLine bLine = b.vcfLineQueue.peek();
			if (aLine == null) {
				sleep();
				aLine = a.vcfLineQueue.peek();
				if(aLine == null) {
					return 1;
				}
			}else if (bLine == null) {
				sleep();
				bLine = b.vcfLineQueue.peek();
				if(bLine == null) {
					return 1;
				}
			}
			if(aLine.chromosome.equals(bLine.chromosome)) {
				if(aLine.offset.equals(bLine.offset)) {
					return aLine.alt.compareTo(bLine.alt);
				}else {
					return aLine.offset.compareTo(bLine.offset);
				}
			}else {
				return aLine.chromosome.compareTo(bLine.chromosome);
			}
		});

		lastLineProcessed = producers[0].vcfLineQueue.poll();
		return lastLineProcessed;
	}

	private static void saveVariantStore(File storageDir, VariantStore store,
			FileBackedByteIndexedStorage<Integer, ConcurrentHashMap<String, VariantMasks>>[] variantMaskStorage)
			throws IOException, FileNotFoundException {
		store.variantMaskStorage = variantMaskStorage;
		try (
				FileOutputStream fos = new FileOutputStream(new File(storageDir, "variantStore.javabin"));
				GZIPOutputStream gzos = new GZIPOutputStream(fos);
				ObjectOutputStream oos = new ObjectOutputStream(gzos);				
				){
			oos.writeObject(store);			
		}
		store = null;
		variantMaskStorage = null;
		System.out.println("Done saving variant masks.");
	}

	private static void saveInfoStores(File storageDir, long startTime, ConcurrentHashMap<String, InfoStore> infoStores)
			throws IOException, FileNotFoundException {
		try (
				FileOutputStream fos = new FileOutputStream(new File(storageDir, "infoStores.javabin"));
				GZIPOutputStream gzos = new GZIPOutputStream(fos);
				ObjectOutputStream oos = new ObjectOutputStream(gzos);			
				){
			oos.writeObject(infoStores);
		}

		System.out.println("Done saving info.");
		System.out.println("completed load in " + (System.currentTimeMillis() - startTime) + " seconds");
	}

	private static void splitInfoStoresByColumn(long startTime) throws FileNotFoundException, IOException {
		System.out.println("Splitting" + (System.currentTimeMillis() - startTime) + " seconds");
		try {
			VCFPerPatientInfoStoreSplitter.splitAll();
		} catch (ClassNotFoundException | InterruptedException | ExecutionException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		System.out.println("Split" + (System.currentTimeMillis() - startTime) + " seconds");
	}

	private static void convertInfoStoresToByteIndexed(long startTime) throws FileNotFoundException, IOException {
		System.out.println("Converting" + (System.currentTimeMillis() - startTime) + " seconds");

		try {
			VCFPerPatientInfoStoreToFBBIISConverter.convertAll("/opt/local/hpds/merged", "/opt/local/hpds/all");
		} catch (ClassNotFoundException | InterruptedException | ExecutionException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		System.out.println("Converted" + (System.currentTimeMillis() - startTime) + " seconds");
	}

	private static HashMap<String, String[]> flipChunk(File storageDir, ConcurrentHashMap<String, InfoStore> infoStores,
			FileBackedByteIndexedStorage<Integer, ConcurrentHashMap<String, VariantMasks>>[] variantMaskStorage,
			VCFLine lastLineProcessed, int lastChromosomeProcessed, int lastChunkProcessed,
			HashMap<String, String[]> loadingMap, ExecutorService ex, boolean isLastChunk) throws IOException, FileNotFoundException {
		if(isLastChunk || lastLineProcessed.chromosome>lastChromosomeProcessed) {
			File infoFile = new File(storageDir, lastChromosomeProcessed + "_infoStores.javabin");
			System.out.println("Flipping info : " + infoFile.getAbsolutePath());
			try (
					FileOutputStream fos = new FileOutputStream(infoFile);
					GZIPOutputStream gzos = new GZIPOutputStream(fos);
					ObjectOutputStream oos = new ObjectOutputStream(gzos);			
					){
				oos.writeObject(infoStores);
			}
			for(String key : infoStores.keySet()) {
				infoStores.get(key).allValues.clear();
			}
		}
		FileBackedByteIndexedStorage<Integer, ConcurrentHashMap<String, VariantMasks>>[] variantMaskStorage_f = variantMaskStorage;
		HashMap<String, String[]> loadingMap_f = loadingMap;
		int lastChromosomeProcessed_f = lastChromosomeProcessed;
		int lastChunkProcessed_f = lastChunkProcessed;
		loadingMap = new HashMap<>();
		ex.submit(()->{
			try {
				if(variantMaskStorage_f[lastChromosomeProcessed_f]==null) {
					variantMaskStorage_f[lastChromosomeProcessed_f] = new FileBackedByteIndexedStorage(Integer.class, ConcurrentHashMap.class, new File(storageDir, "chr" + lastChromosomeProcessed_f + "masks.bin"));							
				}
				variantMaskStorage_f[lastChromosomeProcessed_f].put(lastChunkProcessed_f, convertLoadingMapToMaskMap(loadingMap_f));
			} catch (IOException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		});
		return loadingMap;
	}

	private static ConcurrentHashMap<String, VariantMasks> convertLoadingMapToMaskMap(HashMap<String, String[]> loadingMap) {
		ConcurrentHashMap<String, VariantMasks> maskMap = new ConcurrentHashMap<>();
		loadingMap.entrySet().parallelStream().forEach((entry)->{
			maskMap.put(entry.getKey(), new VariantMasks(entry.getValue()));
		});
		return maskMap;
	}

	private static void sleep() {
		try {
			Thread.sleep(100);
		} catch (InterruptedException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}

}
