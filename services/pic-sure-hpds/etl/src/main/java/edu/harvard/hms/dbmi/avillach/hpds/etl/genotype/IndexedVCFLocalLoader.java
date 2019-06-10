package edu.harvard.hms.dbmi.avillach.hpds.etl.genotype;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.ObjectOutputStream;
import java.io.SequenceInputStream;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
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

import org.apache.commons.compress.utils.IOUtils;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;

import com.google.common.collect.ImmutableMap;

import edu.harvard.hms.dbmi.avillach.hpds.data.genotype.InfoStore;
import edu.harvard.hms.dbmi.avillach.hpds.data.genotype.VariantMasks;
import edu.harvard.hms.dbmi.avillach.hpds.data.genotype.VariantStore;
import edu.harvard.hms.dbmi.avillach.hpds.storage.FileBackedByteIndexedStorage;
import htsjdk.samtools.util.BlockCompressedInputStream;


public class IndexedVCFLocalLoader {

	public static void main(String[] args) throws FileNotFoundException, IOException {
		File indexFile = new File("/opt/local/hpds/vcfIndex.tsv");
		File storageDir = new File("/opt/local/hpds/vcfOutput");

		loadVCFs(indexFile, storageDir);
	}

	static List<VCFLineProducer> producers = new ArrayList<>();

	private static void loadVCFs(File vcfIndexFile, File storageDir) throws FileNotFoundException, IOException {
		long startTime = System.currentTimeMillis();

		HashMap<String/*patient id*/, VCFLineProducer/*patient specific producer*/> patientProducerMap;
		patientProducerMap = createAndStartProducers(vcfIndexFile);

		TreeSet<String> patientIds = new TreeSet<>(
				producers.stream().map(producer->{return producer.patientId;})
				.collect(Collectors.toList()));

		HashMap<String, Integer> patientIdMap = logPatientIds(patientIds);

		System.out.println("Priming Queues");
		primeQueues();

		ConcurrentHashMap<String, InfoStore> infoStores = new ConcurrentHashMap<String, InfoStore>();
		VariantStore store = new VariantStore();
		store.setPatientIds(patientIds.toArray(new String[0]));
		FileBackedByteIndexedStorage<Integer, ConcurrentHashMap<String, VariantMasks>>[] variantMaskStorage = 
				new FileBackedByteIndexedStorage[24];

		System.out.println("Parsing Headers");
		parseHeaders(infoStores);

		VCFLine[] lastLineProcessed = new VCFLine[1];
		int lastChromosomeProcessed = 0;
		int lastChunkProcessed = 0;
		HashMap<String, String[]> loadingMap = new HashMap<>();

		System.out.println("Processing Variants");

		/*
		 *  This executor is used to offload the work of converting loading maps to mask maps. 
		 *  During this conversion each 0/1, 0/1, 0/0 etc value for each patient is changed to a
		 *  single bit in the appropriate zygosity bitmask of a VariantMask.
		 */
		ExecutorService ex = Executors.newFixedThreadPool(1);
		ArrayList<VCFLine> lowestLines;
		do {
			lowestLines = getLowestChromosomalOffsetLines();
			if(lowestLines.isEmpty()) {
				lowestLines = getLowestChromosomalOffsetLines();
			}
			if(!lowestLines.isEmpty()) {
				lastLineProcessed[0] = lowestLines.get(0);
			}
			int currentChunk = lastLineProcessed[0].offset/1000;
			if(lastLineProcessed[0].chromosome>lastChromosomeProcessed || currentChunk > lastChunkProcessed) {
				loadingMap = flipChunk(storageDir, infoStores, variantMaskStorage, lastLineProcessed[0],
						lastChromosomeProcessed, lastChunkProcessed, loadingMap, ex, false);
				lastChromosomeProcessed = lastLineProcessed[0].chromosome;
				lastChunkProcessed = currentChunk;
				if(lastChunkProcessed % 10 == 0) {
					System.out.println(System.currentTimeMillis() + " Done loading chunk : " + lastChunkProcessed + " for chromosome " + lastChromosomeProcessed);
				}
			}
			HashMap<String, String[]>[] loadingMap_f = new HashMap[] {loadingMap};
			lowestLines.parallelStream().forEach((line)->{
				if(line != null) {
					processLine(patientIds, patientIdMap, infoStores, line, loadingMap_f[0]);
				}
			});
		} while(producers.stream().anyMatch(
				producer->{return !producer.vcfLineQueue.isEmpty() || producer.isRunning(); }) || !lowestLines.isEmpty());
		// Don't forget to save the last chunk.
		loadingMap = flipChunk(storageDir, infoStores, variantMaskStorage, lastLineProcessed[0],
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

	private static String lastSpecNotation = null;

	private static String[] variantValues;

	private static void processLine(TreeSet<String> patientIds, HashMap<String, Integer> patientIdMap,
			ConcurrentHashMap<String, InfoStore> infoStores, VCFLine lastLineProcessed,
			HashMap<String, String[]> loadingMap) {
		final String specNotation = lastLineProcessed.specNotation();
		if( lastSpecNotation == null || ! specNotation.contentEquals(lastSpecNotation)) {
			synchronized(producers) {
				variantValues = loadingMap.get(specNotation);
				if(variantValues==null) {
					variantValues = new String[patientIds.size()];
					loadingMap.put(specNotation, variantValues);
				}
				lastSpecNotation = specNotation;
			}
		}
		VCFLineProducer producer = producers.get(0);
		String value = lastLineProcessed.data == null ? "0/0" : 
			lastLineProcessed.data.substring(0,3);
		int patientIndex = patientIdMap.get(
				lastLineProcessed.patientId);
		variantValues[patientIndex] 
				= value;
		if(producer.processAnnotations) {
			String[] infoValues = (lastLineProcessed.info).split(";");
			infoStores.values().parallelStream().forEach(infoStore->{
				try {
					infoStore.processRecord(specNotation, infoValues);
				}catch(NumberFormatException e) {
					System.out.println("Skipping record due to parsing exception : " + String.join(",", infoValues));
				}
			});			
		}
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
	public static final int VCF_LINE_QUEUE_SIZE = 10000;


	private static HashMap<String/*patient id*/, VCFLineProducer/*patient specific producer*/> 
	createAndStartProducers(File vcfIndexFile) {

		CSVParser parser;
		try {
			parser = CSVParser.parse(vcfIndexFile, Charset.forName("UTF-8"), CSVFormat.DEFAULT.withDelimiter('\t').withSkipHeaderRecord(true));

			HashMap<String, VCFLineProducer> ret = new HashMap<String, VCFLineProducer>();

			HashMap<String/*patientId*/, TreeMap<Integer/*chromosome*/, File/*vcf file with this chromosome for this patient*/>>
			patientChromosomeFileMap = new HashMap<>();

			final boolean[] horribleHeaderSkipFlag = {false};
			TreeMap<Integer, VCFLineProducer> producerMap = new TreeMap<Integer, VCFLineProducer>();
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
					for(int sampleIndex = 0; sampleIndex<sampleIds.length; sampleIndex++) {
						TreeMap<Integer, File> chromosomeFileMap = patientChromosomeFileMap.get(patientIds[sampleIndex]);
						if(chromosomeFileMap == null) {
							chromosomeFileMap = new TreeMap<Integer, File>();
							patientChromosomeFileMap.put(patientIds[sampleIndex], chromosomeFileMap);
						}
						chromosomeFileMap.put(chromosome, vcf);
						VCFLineProducer producer = producerMap.get(sampleIndex);
						if(producer==null) {
							producer = 
									new VCFLineProducer(
											sampleIndex, processAnnotations, vcfIsGzipped, 
											chromosomeFileMap, patientIds[sampleIndex], 
											new ArrayBlockingQueue<>(VCF_LINE_QUEUE_SIZE));							
							producerMap.put(sampleIndex, producer);
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
			return ret;
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

		System.out.println("Producers are primed.");
	}

	private static void parseHeaders(ConcurrentHashMap<String, InfoStore> infoStores) {
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
		VCFLine lastLineProcessed;
		Thread.yield();
		producers.sort((a, b)->{
			VCFLine aLine = a.vcfLineQueue.peek();
			VCFLine bLine = b.vcfLineQueue.peek();
			if (aLine == null) {
				sleep();
				aLine = a.vcfLineQueue.peek();
				if(aLine == null) {
					return 1;
				}
			} 
			if (bLine == null) {
				sleep();
				bLine = b.vcfLineQueue.peek();
				if(bLine == null) {
					return -1;
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
		ArrayList<VCFLine> lowestLines = new ArrayList<VCFLine>();
		lastLineProcessed = producers.get(0).vcfLineQueue.poll();
		if(lastLineProcessed != null) {
			lowestLines.add(lastLineProcessed);
			for(int x = 1;x<producers.size();x++) {
				VCFLineProducer vcfLineProducer = producers.get(x);
				VCFLine producerLine = vcfLineProducer.vcfLineQueue.peek();
				if(producerLine != null && producerLine.chromosome.equals(lastLineProcessed.chromosome) && producerLine.offset.equals(lastLineProcessed.offset) && producerLine.alt.contentEquals(lastLineProcessed.alt)) {
					lowestLines.add(vcfLineProducer.vcfLineQueue.poll());
				} else {
					x=producers.size();
				}
			}
		}
		return lowestLines;
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
