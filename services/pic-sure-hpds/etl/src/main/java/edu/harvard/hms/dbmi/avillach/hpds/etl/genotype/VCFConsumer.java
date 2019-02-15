package edu.harvard.hms.dbmi.avillach.hpds.etl.genotype;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.zip.GZIPInputStream;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;

import edu.harvard.hms.dbmi.avillach.hpds.data.genotype.VariantMasks;
import edu.harvard.hms.dbmi.avillach.hpds.data.genotype.VariantSpec;
import edu.harvard.hms.dbmi.avillach.hpds.data.genotype.VariantStore;

public class VCFConsumer implements Consumer<File> {
	private final File sourceDir;
	private transient int currentInfoChromosome;
	private transient int currentInfoBucket;
	private transient HashMap<String, String> currentInfoStorageBucket;
	private transient int currentMaskChromosome;
	private transient int currentMaskBucket;
	private transient VariantStore store;
	private transient HashMap<String, VariantMasks> currentMaskStorageBucket;
	private transient int currentQualityChromosome;
	private transient int currentQualityBucket;
	private transient HashMap<String, Integer> currentQualityStorageBucket;

	public static int CHR = 0, OFF = 1, NAME = 2, REF = 3, ALT = 4, QUAL = 5, FILTER = 6, INFO = 7;

	public VCFConsumer(File sourceDir, VariantStore store) {
		this.store = store;
		this.sourceDir = sourceDir;
	}

	private void storeVariantQuality(CSVRecord record, VariantSpec spec) throws IOException {
		if(currentQualityChromosome == 0 || currentQualityChromosome != spec.metadata.chromosome) {
			currentQualityChromosome = spec.metadata.chromosome;
			currentQualityBucket = spec.metadata.offset/10000;
			currentQualityStorageBucket = new HashMap<String, Integer>();
		}
		swapQualityStorage(record, spec);
		currentQualityStorageBucket.put(spec.specNotation(), spec.metadata.qual);
	}

	private void swapQualityStorage(CSVRecord record, VariantSpec spec) throws IOException {
		if(spec.metadata.chromosome != currentQualityChromosome || spec.metadata.offset/10000 != currentQualityBucket) {
			closeQualityStorage();
			currentQualityChromosome = spec.metadata.chromosome;
			currentQualityBucket = spec.metadata.offset/10000;
			currentQualityStorageBucket = new HashMap<String, Integer>();			
		}
	}

	private void closeQualityStorage() throws IOException {
		store.variantQualityStorage[currentQualityChromosome].put(currentQualityBucket, currentQualityStorageBucket);
	}

	private void storeVariantInfo(CSVRecord record, VariantSpec spec) throws IOException {
		if(currentInfoChromosome == 0 || currentInfoChromosome != spec.metadata.chromosome) {
			currentInfoChromosome = spec.metadata.chromosome;
			currentInfoBucket = spec.metadata.offset/10000;
			currentInfoStorageBucket = new HashMap<String, String>();
		}
		swapInfoStorage(spec);
		currentInfoStorageBucket.put(spec.specNotation(), record.get(INFO));
	}

	private void swapInfoStorage(VariantSpec spec) throws IOException {
		if(spec.metadata.chromosome != currentInfoChromosome || spec.metadata.offset/10000 != currentInfoBucket) {
			closeInfoStorage();
			currentInfoChromosome = spec.metadata.chromosome;
			currentInfoBucket = spec.metadata.offset/10000;
			currentInfoStorageBucket = new HashMap<String, String>();			
		}
	}

	private void closeInfoStorage() throws IOException {
		store.variantInfoStorage[currentMaskChromosome].put(currentInfoBucket, currentInfoStorageBucket);
	}

	private void storeVariantMasks(CSVRecord record, VariantSpec spec) throws IOException {
		if(currentMaskChromosome == 0 || currentMaskChromosome != spec.metadata.chromosome) {
			currentMaskChromosome = spec.metadata.chromosome;
			currentMaskBucket = spec.metadata.offset/10000;
			currentMaskStorageBucket = new HashMap<String, VariantMasks>();
		}
		swapMaskStorage(spec);
		currentMaskStorageBucket.put(spec.specNotation(), new VariantMasks(record));
	}

	private void swapMaskStorage(VariantSpec spec) throws IOException {
		if(spec.metadata.chromosome != currentMaskChromosome || spec.metadata.offset/10000 != currentMaskBucket) {
			if(spec.metadata.offset/10000 < currentInfoBucket) {
				throw new RuntimeException("offset less than current bucket at " + spec.specNotation());
			}
			closeMaskStorage();
			currentMaskChromosome = spec.metadata.chromosome;
			currentMaskBucket = spec.metadata.offset/10000;
			currentMaskStorageBucket = new HashMap<String, VariantMasks>();			
		}
	}

	private void closeMaskStorage() throws IOException {
		store.variantMaskStorage[currentMaskChromosome].put(currentMaskBucket, currentMaskStorageBucket);
	}

	private void readHeader(BufferedReader reader, String filename, File mappingFile) throws IOException {
		ArrayList<String> headerLines = new ArrayList<String>();
		String line = reader.readLine();
		while(line.startsWith("##")) {
			headerLines.add(line);
			line = reader.readLine();
		}
		headerLines.add(line);
		String[] headerColumns = line.split("\t");
		store.setPatientIds(mapPatientIds(mappingFile, Arrays.copyOfRange(headerColumns, 9, headerColumns.length)));
		calculateVariantStorageSize();
	}

	private void calculateVariantStorageSize() throws IOException {
		String sizingBitMap = "11";
		for(int x = 0;x < store.getPatientIds().length;x++) {
			sizingBitMap += "0";
		}
		sizingBitMap += "11";
		BigInteger sizingBigInt = new BigInteger(sizingBitMap, 2);
		byte[] sizingArray = sizingBigInt.toByteArray();
		store.setVariantStorageSize(sizingArray.length);

		store.emptyBitmask = sizingBigInt;
	}

	private static String[] mapPatientIds(File mappingFile, String[] sampleIds) throws IOException {
		BufferedReader mappingIn = new BufferedReader(new FileReader(mappingFile));
		for(int x = 0;x<11;x++) {
			mappingIn.readLine();
		}
		CSVParser parser = CSVFormat.DEFAULT.withDelimiter('\t').parse(mappingIn);
		HashMap<String, String> sampleIdToDbGapSubjectId = new HashMap<>();
		parser.forEach((CSVRecord record)->{
			sampleIdToDbGapSubjectId.put(record.get(4), record.get(0));
		});
		return Arrays.stream(sampleIds).map((id)->{return sampleIdToDbGapSubjectId.get(id);}).collect(Collectors.toList()).toArray(new String[0]);
	}

	@Override
	public void accept(File file) {
		try {
			System.out.println("Starting : " + file.getName());
			Reader vcfReader = file.getName().endsWith("vcf.bgz") ? new InputStreamReader(new GZIPInputStream(new FileInputStream(file))) :  new FileReader(file);
			BufferedReader reader = new BufferedReader(vcfReader);
			readHeader(reader, file.getName(), new File(sourceDir, "mapping.txt"));
			CSVParser parser = CSVFormat.DEFAULT.withDelimiter('\t').parse(reader);
			long count = 0;
			for(CSVRecord record:parser) {
				VariantSpec spec = new VariantSpec(record);
				storeVariantMasks(record, spec);
				storeVariantInfo(record, spec);
				storeVariantQuality(record, spec);
				count++;
				if(count%100000==0) {
					System.out.println(count + " total lines read, currently on " + spec.specNotation());
				}
			}
			closeMaskStorage();
			closeInfoStorage();
			closeQualityStorage();
		}catch(Exception e) {
			e.printStackTrace();
		}
	}
}