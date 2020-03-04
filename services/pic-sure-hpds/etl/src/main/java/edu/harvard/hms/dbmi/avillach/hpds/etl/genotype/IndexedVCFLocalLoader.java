package edu.harvard.hms.dbmi.avillach.hpds.etl.genotype;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectOutputStream;
import java.lang.management.ManagementFactory;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
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

import javax.management.MBeanServer;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;

import com.sun.management.HotSpotDiagnosticMXBean;

import edu.harvard.hms.dbmi.avillach.hpds.data.genotype.InfoStore;
import edu.harvard.hms.dbmi.avillach.hpds.data.genotype.VariantMasks;
import edu.harvard.hms.dbmi.avillach.hpds.data.genotype.VariantStore;
import edu.harvard.hms.dbmi.avillach.hpds.storage.FileBackedByteIndexedStorage;

public class IndexedVCFLocalLoader {

	public static void main(String[] args) throws FileNotFoundException, IOException {
		File indexFile = new File("/opt/local/hpds/vcfIndex.tsv");
		File storageDir = new File("/opt/local/hpds/all");

		loadVCFs(indexFile, storageDir);
	}

	static List<VCFLineProducer> producers = new ArrayList<>();
	static TreeSet<String> patientIds = null;
	static Map<String, Integer> patientIdMap = null;
	static HashMap<String, String[]> loadingMap = null;
	static File storageDir = null;
	static ExecutorService chunkWriteEx = Executors.newFixedThreadPool(1);
	static ConcurrentHashMap<String, InfoStore> infoStores = new ConcurrentHashMap<String, InfoStore>();
	static long startTime = System.currentTimeMillis();


	private static void loadVCFs(File vcfIndexFile, File storageDirectory) throws FileNotFoundException, IOException {

		storageDir = storageDirectory;

		createAndStartProducers(vcfIndexFile);

		patientIds = new TreeSet<>(
				producers.stream().map(producer->{return producer.patientId;})
				.collect(Collectors.toList()));

		patientIdMap = Collections.synchronizedMap(mapPatientIds());

		System.out.println("Priming Queues");
		primeQueues();
		System.out.println("Queues are primed.");

		VariantStore store = new VariantStore();
		store.setPatientIds(patientIds.toArray(new String[0]));
		FileBackedByteIndexedStorage<Integer, ConcurrentHashMap<String, VariantMasks>>[] variantMaskStorage = 
				new FileBackedByteIndexedStorage[24];

		System.out.println("Parsing Headers");
		parseHeaders();
		System.out.println(infoStores.size() + " INFO columns detected ");

		VCFLine lastLineProcessed = null;
		int lastChromosomeProcessed = 0;
		int lastChunkProcessed = 0;
		loadingMap = new HashMap<String, String[]>();

		System.out.println("Processing Variants");

		/*
		 *  This executor is used to offload the work of converting loading maps to mask maps. 
		 *  During this conversion each 0/1, 0/1, 0/0 etc value for each patient is changed to a
		 *  single bit in the appropriate zygosity bitmask of a VariantMask.
		 */
		ArrayList<VCFLine> lowestLines;
		int currentChunk = 0;
		int currentChromosome = 0;
		do {
			lowestLines = allowLowestLinesToPopulate();
			if(!lowestLines.isEmpty()) {
				lastLineProcessed = lowestLines.get(0);
				currentChunk = lastLineProcessed.offset/1000;
				currentChromosome = lastLineProcessed.chromosome;
				if(currentChromosome > lastChromosomeProcessed || currentChunk > lastChunkProcessed) {
					loadingMap = flipChunk(variantMaskStorage, lastLineProcessed,
							lastChromosomeProcessed, lastChunkProcessed, false);
					lastChromosomeProcessed = lastLineProcessed.chromosome;
					lastChunkProcessed = currentChunk;
					if(lastChunkProcessed % 10 == 0) {
						System.out.println(System.currentTimeMillis() + " Done loading chunk : " + lastChunkProcessed + " for chromosome " + lastChromosomeProcessed);
					}
				}

				lowestLines.parallelStream().forEach((line)->{
					if(line != null) {
						processLine(line, loadingMap);
					}
				});
			}
		} while(producers.stream().anyMatch(
				producer->{return !producer.vcfLineQueue.isEmpty() || producer.isRunning(); }) || !lowestLines.isEmpty());
		// Don't forget to save the last chunk.
		loadingMap = flipChunk(variantMaskStorage, lastLineProcessed,
				lastChromosomeProcessed, lastChunkProcessed, true);

		shutdownChunkWriteExecutor();

		System.out.println("Done processing VCFs.");

		saveVariantStore(store, variantMaskStorage);

		saveInfoStores();

		splitInfoStoresByColumn();

		convertInfoStoresToByteIndexed();
	}

	private static ArrayList<VCFLine> allowLowestLinesToPopulate() {
		ArrayList<VCFLine> lowestLines;
		ensureQueuesNotEmpty();
		lowestLines = getLowestChromosomalOffsetLines();
		return lowestLines;
	}

	private static void shutdownChunkWriteExecutor() {
		chunkWriteEx.shutdown();
		while(!chunkWriteEx.isTerminated()) {
			try {
				System.out.println(((ThreadPoolExecutor)chunkWriteEx).getQueue().size() + " writes left to be written.");
				chunkWriteEx.awaitTermination(20, TimeUnit.SECONDS);
			} catch (InterruptedException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}			
		}
	}

	private static String lastSpecNotation = null;

	private static String[] variantValues;

	private static String lastInfoLineProcessed = "";

	private static void processLine(VCFLine lastLineProcessed,
			HashMap<String, String[]> loadingMap) {
		final String specNotation = lastLineProcessed.specNotation();
		if( variantValues == null || lastSpecNotation == null || ! specNotation.contentEquals(lastSpecNotation)) {
			synchronized(producers) {
				variantValues = loadingMap.get(specNotation);
				if(variantValues==null) {
					variantValues = new String[patientIds.size()];
					loadingMap.put(specNotation, variantValues);
					if(!(lastLineProcessed.name == null || lastLineProcessed.name.contentEquals("."))) {
						loadingMap.put(lastLineProcessed.name, variantValues);
					}
				}
				lastSpecNotation = specNotation;
			}
		}
		VCFLineProducer producer = producers.get(0);
		String value = lastLineProcessed.data == null ? "0/0" : 
			lastLineProcessed.data.substring(0,3);
		Integer patientIndex = patientIdMap.get(
				lastLineProcessed.patientId.trim());
		synchronized(producers) {
			if(patientIndex == null||variantValues == null) {
				System.out.println(specNotation + " patientId : " + lastLineProcessed.patientId + " variantValues : " + variantValues + " value : " + value + " : " + patientIndex);
				System.out.println(patientIndex);
				for(String key : patientIdMap.keySet()) {
					System.out.println(key + " : " + patientIdMap.get(key));
				}
				variantValues[patientIndex]
						= value;
				System.exit(-1);
			}else {
				variantValues[patientIndex]
						= value;			
			}
		}
		if(producer.processAnnotations && !lastLineProcessed.info.contentEquals(lastInfoLineProcessed)) {
			String[] infoValues = (lastLineProcessed.info).split("[;&]");
			infoStores.values().parallelStream().forEach(infoStore->{
				try {
					infoStore.processRecord(specNotation, infoValues);
				}catch(NumberFormatException e) {
					System.out.println("Skipping record due to parsing exception : " + String.join(",", infoValues));
				}
			});
			lastInfoLineProcessed = lastLineProcessed.info;
		}
	}

	private static HashMap<String, Integer> mapPatientIds() {
		HashMap<String, Integer> patientIdMap = new HashMap<String, Integer>(patientIds.size());
		int x = 0;
		for(String patientId : patientIds) {
			patientIdMap.put(patientId.trim(), x++);
		}

		System.out.println("Listed all patients. " + patientIds.size());
		return patientIdMap;
	}

	private static final int 
	FILE_COLUMN = 0,
	CHROMOSOME_COLUMN = 1,
	ANNOTATED_FLAG_COLUMN = 2,
	GZIP_FLAG_COLUMN=3,
	SAMPLE_IDS_COLUMN=4,
	PATIENT_IDS_COLUMN=5,
	//These columns are to support a future feature, ignore them for now.
	SAMPLE_RELATIONSHIPS_COLUMN=6,
	RELATED_SAMPLE_IDS_COLUMN=7
	;
	public static final int VCF_LINE_QUEUE_SIZE = 2000;

	private static void createAndStartProducers(File vcfIndexFile) {

		CSVParser parser;
		try {
			parser = CSVParser.parse(vcfIndexFile, Charset.forName("UTF-8"), CSVFormat.DEFAULT.withDelimiter('\t').withSkipHeaderRecord(true));

			HashMap<String, VCFLineProducer> ret = new HashMap<String, VCFLineProducer>();

			HashMap<String/*patientId*/, TreeMap<Integer/*chromosome*/, File/*vcf file with this chromosome for this patient*/>>
			patientChromosomeFileMap = new HashMap<>();

			final boolean[] horribleHeaderSkipFlag = {false};
			TreeMap<Integer, VCFLineProducer> producerMap = new TreeMap<Integer, VCFLineProducer>();

			int[] sampleIndex = {0};
			parser.forEach((CSVRecord r)->{
				if(horribleHeaderSkipFlag[0]) {
					File vcf = new File(r.get(FILE_COLUMN));
					int chromosome = r.get(CHROMOSOME_COLUMN).contentEquals("ALL") ? 
							0 : Integer.parseInt(r.get(CHROMOSOME_COLUMN));
					boolean processAnnotations = 
							Integer.parseInt(r.get(ANNOTATED_FLAG_COLUMN))==1;
					boolean vcfIsGzipped = Integer.parseInt(r.get(GZIP_FLAG_COLUMN))==1;
					String[] sampleIds = r.get(SAMPLE_IDS_COLUMN).split(",");
					String[] patientIds = r.get(PATIENT_IDS_COLUMN).split(",");
					for(sampleIndex[0] = sampleIndex[0]; sampleIndex[0]<sampleIds.length; sampleIndex[0]++) {
						TreeMap<Integer, File> chromosomeFileMap = patientChromosomeFileMap.get(patientIds[sampleIndex[0]]);
						if(chromosomeFileMap == null) {
							chromosomeFileMap = new TreeMap<Integer, File>();
							patientChromosomeFileMap.put(patientIds[sampleIndex[0]], chromosomeFileMap);
						}
						chromosomeFileMap.put(chromosome, vcf);
						VCFLineProducer producer = producerMap.get(sampleIndex[0]);
						if(producer==null) {
							producer = 
									new VCFLineProducer(
											sampleIndex[0], processAnnotations, vcfIsGzipped, 
											chromosomeFileMap, patientIds[sampleIndex[0]], 
											new ArrayBlockingQueue<>(VCF_LINE_QUEUE_SIZE));							
							producerMap.put(sampleIndex[0], producer);
						}
					}
				}else {
					horribleHeaderSkipFlag[0] = true;
				}
			});

			producerMap.values().stream().forEach((producer)->{
				System.out.println("Starting : " + producer.patientId);
				producer.start();
				producers.add(producer);
			});

			System.out.println("Started all producers. " + producers.size());
		} catch (IOException e) {
			throw new RuntimeException("IOException caught parsing vcfIndexFile", e);
		}
	}

	private static void primeQueues() {
		// wait until all queues are primed
		for(ArrayBlockingQueue<VCFLine> queue : producers.stream()
				.map(producer->{return producer.vcfLineQueue;})
				.collect(Collectors.toList())) {
			while(queue.size() < VCF_LINE_QUEUE_SIZE) {
				sleep();
			}
		}
	}

	private static void ensureQueuesNotEmpty() {
		// wait until all queues are primed
		for(VCFLineProducer producer : producers) {
			while(producer.isRunning() && producer.vcfLineQueue.size() < 1) {
				sleep();
			}
		}
	}

	private static void parseHeaders() {
		for(VCFLineProducer producer : producers) {
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

	private static ArrayList<VCFLine> getLowestChromosomalOffsetLines() {
		ArrayList<VCFLine> lines = new ArrayList<>(producers.parallelStream()
				.filter((producer)->{return !producer.vcfLineQueue.isEmpty();})
				.map((producer)->{return producer.vcfLineQueue.peek();})
				.collect(Collectors.toList()));
		if(lines.isEmpty()) {
			return lines;
		}
		VCFLine lowestLine = Collections.min(lines);
		ArrayList<VCFLine> lowestLines = new ArrayList<VCFLine>();
		producers.parallelStream().filter((producer)->{
			VCFLine producerLine = producer.vcfLineQueue.peek();
			return producerLine != null 
					&& producerLine.compareTo(lowestLine) == 0;
		}).forEach((producer)->{
			VCFLine line;
			try {
				line = producer.vcfLineQueue.take();
				synchronized(lowestLines) {
					lowestLines.add(line);
				}
			} catch (InterruptedException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		});
		return lowestLines;
	}

	private static void saveVariantStore(VariantStore store,
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

	private static void saveInfoStores()
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

	private static void splitInfoStoresByColumn() throws FileNotFoundException, IOException {
		System.out.println("Splitting" + (System.currentTimeMillis() - startTime) + " seconds");
		try {
			VCFPerPatientInfoStoreSplitter.splitAll();
		} catch (ClassNotFoundException | InterruptedException | ExecutionException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		System.out.println("Split" + (System.currentTimeMillis() - startTime) + " seconds");
	}

	private static void convertInfoStoresToByteIndexed() throws FileNotFoundException, IOException {
		System.out.println("Converting" + (System.currentTimeMillis() - startTime) + " seconds");
		try {
			VCFPerPatientInfoStoreToFBBIISConverter.convertAll("/opt/local/hpds/merged", "/opt/local/hpds/all");
		} catch (ClassNotFoundException | InterruptedException | ExecutionException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		System.out.println("Converted " + ((System.currentTimeMillis() - startTime)/1000) + " seconds");
	}

	static boolean[] infoStoreFlipped = new boolean[24];

	private static HashMap<String, String[]> flipChunk(
			FileBackedByteIndexedStorage<Integer, ConcurrentHashMap<String, VariantMasks>>[] variantMaskStorage,
			VCFLine lastLineProcessed, int lastChromosomeProcessed, int lastChunkProcessed,
			boolean isLastChunk) throws IOException, FileNotFoundException {
		if(isLastChunk || lastLineProcessed.chromosome>lastChromosomeProcessed) {
			if(!infoStoreFlipped[lastChromosomeProcessed]) {
				infoStoreFlipped[lastChromosomeProcessed] = true;
				File infoFile = new File(storageDir, lastChromosomeProcessed + "_infoStores.javabin");
				System.out.println(Thread.currentThread().getName() + " : " + "Flipping info : " + infoFile.getAbsolutePath() + " " + isLastChunk + " " + lastLineProcessed.chromosome + " " + lastChromosomeProcessed + " ");
				try (
						FileOutputStream fos = new FileOutputStream(infoFile);
						GZIPOutputStream gzos = new GZIPOutputStream(fos);
						ObjectOutputStream oos = new ObjectOutputStream(gzos);			
						){
					oos.writeObject(infoStores);
				}
				ConcurrentHashMap<String, InfoStore> newInfoStores = new ConcurrentHashMap<String, InfoStore>();
				for(String key : infoStores.keySet()) {
					newInfoStores.put(key, new InfoStore(infoStores.get(key).description, ";", key));
				}
				infoStores = newInfoStores;
			}
		}
		FileBackedByteIndexedStorage<Integer, ConcurrentHashMap<String, VariantMasks>>[] variantMaskStorage_f = variantMaskStorage;
		HashMap<String, String[]> loadingMap_f = loadingMap;
		int lastChromosomeProcessed_f = lastChromosomeProcessed;
		int lastChunkProcessed_f = lastChunkProcessed;
		loadingMap = new HashMap<>();
		chunkWriteEx.submit(()->{
			try {
				if(variantMaskStorage_f[lastChromosomeProcessed_f]==null) {
					variantMaskStorage_f[lastChromosomeProcessed_f] = 
							new FileBackedByteIndexedStorage(Integer.class, ConcurrentHashMap.class, 
									new File(storageDir, "chr" + lastChromosomeProcessed_f + "masks.bin"));							
				}
				variantMaskStorage_f[lastChromosomeProcessed_f].put(lastChunkProcessed_f, convertLoadingMapToMaskMap(loadingMap_f));
			} catch (IOException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		});
		return loadingMap;
	}

	public static void dumpHeap(String filePath, boolean live) throws IOException {
		MBeanServer server = ManagementFactory.getPlatformMBeanServer();
		HotSpotDiagnosticMXBean mxBean = ManagementFactory.newPlatformMXBeanProxy(
				server, "com.sun.management:type=HotSpotDiagnostic", HotSpotDiagnosticMXBean.class);
		mxBean.dumpHeap(filePath, live);
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
