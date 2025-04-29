package edu.harvard.hms.dbmi.avillach.hpds.data.genotype;

import java.io.*;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import com.google.common.base.Joiner;
import edu.harvard.hms.dbmi.avillach.hpds.storage.FileBackedJavaIndexedStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import edu.harvard.hms.dbmi.avillach.hpds.data.genotype.caching.VariantBucketHolder;
import edu.harvard.hms.dbmi.avillach.hpds.storage.FileBackedByteIndexedStorage;

/**
 * This class will create a set of FBBIS objects that allow lookups of variant-spec -> metadata, instead of the 
 * metadata -> variant-spec map that is used for searching and identifying patients. 
 * 
 * The loading and reading of this class must take place separately;  the flush() function will write out the in-memory contents to 
 * a fast, disk-based backing store.
 */
public class VariantMetadataIndex implements Serializable {
	public static final String VARIANT_METADATA_FILENAME = "VariantMetadata.javabin";
	public static String VARIANT_METADATA_BIN_FILE = "/opt/local/hpds/all/" + VARIANT_METADATA_FILENAME;
	
	private static final long serialVersionUID = 5917054606643971537L;
	private static Logger log = LoggerFactory.getLogger(VariantMetadataIndex.class); 

	// (String) contig  --> (Integer) Bucket -->  (String) variant spec --> INFO column data[].
	private final Map<String,  FileBackedByteIndexedStorage<Integer, ConcurrentHashMap<String, String[]>> > indexMap = new HashMap<>();

	public static final String VARIANT_METADATA_STORAGE_FILE_PREFIX = "VariantMetadataStorage";
	private String fileStoragePrefix = "/opt/local/hpds/all/" + VARIANT_METADATA_STORAGE_FILE_PREFIX;

	/**
	 * This map allows us to load millions of variants without re-writing the fbbis each time (which would blow up the disk space).
	 * We need to remember to flush() between each contig this gets saved to the fbbis array.
	 */
	private transient Map<String,  ConcurrentHashMap<Integer, ConcurrentHashMap<String, String[]>> > loadingMap = new HashMap<>();
	
	/**
	 * This constructor should only be used for testing; we expect the files to be in the default locations in production
	 * @param storageFile
	 * @throws IOException
	 */
	public VariantMetadataIndex(String storageFile) throws IOException { 
		fileStoragePrefix = storageFile;  
	}  
	
	/**
	 * creates a default metadata index that maps variant spec -> metadata using an array of one file per contig.
	 * @throws IOException
	 */
	public VariantMetadataIndex() throws IOException {  
	}
	
	/**
	 * This will always return a value, including an empty array if there is no data or an error.
	 * @param variantSpec
	 * @return
	 */
	public Set<String> findBySingleVariantSpec(String variantSpec, VariantBucketHolder<String[]> bucketCache) {
		try {
			String[] segments = variantSpec.split(",");
			if (segments.length < 2) {
				log.error("Less than 2 segments found in this variant : " + variantSpec);
			}

			int chrOffset = Integer.parseInt(segments[1]) / VariantStore.BUCKET_SIZE;
			String contig = segments[0];
			
			//see if we can reuse the cache 
			if (bucketCache.lastValue == null || !contig.contentEquals(bucketCache.lastContig)
					|| chrOffset != bucketCache.lastChunkOffset) {
				FileBackedByteIndexedStorage<Integer, ConcurrentHashMap<String, String[]>> ContigFbbis = indexMap.get(contig);
				if(ContigFbbis == null) {
					return Set.of();
				}
				bucketCache.lastValue = ContigFbbis.get(chrOffset);
				bucketCache.lastContig = contig;
				bucketCache.lastChunkOffset = chrOffset;
			}
			
			if( bucketCache.lastValue != null) {
				if(bucketCache.lastValue.get(variantSpec) == null) {
					log.warn("No variant data found for spec " + variantSpec);
					return Set.of();
				}
				return Set.of(bucketCache.lastValue.get(variantSpec));
			}
			log.warn("No bucket found for spec " + variantSpec + " in bucket " + chrOffset);
			return Set.of();
		
		} catch (UncheckedIOException e) {
			log.warn("IOException caught looking up variantSpec : " + variantSpec, e);
			return Set.of();
		}
	}

	public Map<String, Set<String>> findByMultipleVariantSpec(Collection<String> varientSpecList) {
//		log.debug("SPEC list "  + varientSpecList.size() + " :: " + Arrays.deepToString(varientSpecList.toArray()));

		VariantBucketHolder<String[]> bucketCache = new VariantBucketHolder<String[]>();
		return varientSpecList.stream().collect(Collectors.toMap(
				variant->{return variant;},
				variant->{return findBySingleVariantSpec(variant, bucketCache);}
				));
	}

	/**
	 * Get/put symmetry is broken here, since we want the ETL process to build the fbbis objects, so we only 
	 * have to write them to disk once.  The data will be written to disk only when the flush() method is called.
	 * 
	 * @param variantSpec
	 * @throws IOException
	 */
	public void put(String variantSpec, String metaData ) throws IOException {
		
		String[] segments = variantSpec.split(",");
		if (segments.length < 2) {
			log.error("Less than 2 segments found in this variant : " + variantSpec);
		}

		int chrOffset = Integer.parseInt(segments[1]) / VariantStore.BUCKET_SIZE;
		String contig = segments[0];
		
		ConcurrentHashMap<Integer, ConcurrentHashMap<String, String[]>> contigMap = loadingMap.get(contig);
		if(contigMap == null) {
			contigMap = new ConcurrentHashMap<Integer, ConcurrentHashMap<String, String[]>>();
			loadingMap.put(contig, contigMap);
		}
		
		ConcurrentHashMap<String, String[]> bucket = contigMap.get(chrOffset);
		if(bucket == null) {
			bucket = new ConcurrentHashMap<String, String[]>();
			contigMap.put(chrOffset, bucket);
		}
		
		List<String> existingRecords =  new ArrayList<String>();
		if(bucket.get(variantSpec) != null) {
			Collections.addAll(existingRecords, bucket.get(variantSpec));
		}
    	existingRecords.add(metaData);
    	bucket.put(variantSpec, existingRecords.toArray(new String[existingRecords.size()]));
	}

	/**
	 * This will write out the current contents of the in-memory cache to file based storage. it should be called
	 * between processing each contig so that the memory usage doesn't overwhelm the system.
	 * 
	 * This will overwrite any existing data, so it should only be called once per contig.
	 * @throws IOException
	 */
	public synchronized void flush() throws IOException {
		
		for(String contig : loadingMap.keySet()) {
			log.debug("writing contig " + contig);
			
			FileBackedByteIndexedStorage<Integer, ConcurrentHashMap<String, String[]>> contigFbbis = indexMap.get(contig);
			if(contigFbbis == null) {
				log.info("creating new file for " + contig);
				String filePath = fileStoragePrefix + "_" + contig + ".bin";
				contigFbbis = new FileBackedJavaIndexedStorage(Integer.class, ConcurrentHashMap.class, new File(filePath));
				indexMap.put(contig, contigFbbis);
			}
			
			ConcurrentHashMap<Integer, ConcurrentHashMap<String, String[]>> contigMap = loadingMap.get(contig);
			for(Integer bucketNumber : contigMap.keySet()) {
				//make sure we don't lose any existing data
				ConcurrentHashMap<String, String[]> bucketStorage = contigFbbis.get(bucketNumber);
				if(bucketStorage == null) {
					bucketStorage = contigMap.get(bucketNumber);
				} else {
					bucketStorage.putAll(contigMap.get(bucketNumber));
				}
				
				contigFbbis.put(bucketNumber, bucketStorage);
			}
			
			log.debug("Saved " + contig + " to FBBIS");
		}
		//now reset the map
		loadingMap = new HashMap<String,  ConcurrentHashMap<Integer, ConcurrentHashMap<String, String[]>> >();
	}
	
	public void complete() throws IOException {
		flush();
	
		for(String contig : indexMap.keySet()) {
			FileBackedByteIndexedStorage<Integer, ConcurrentHashMap<String, String[]>> contigFbbis = indexMap.get(contig);
			contigFbbis.complete();
		}
		
	}

	public static VariantMetadataIndex createInstance(String metadataIndexPath) {
		try(ObjectInputStream in = new ObjectInputStream(new GZIPInputStream(
				new FileInputStream(metadataIndexPath + VARIANT_METADATA_FILENAME)))){
			VariantMetadataIndex variantMetadataIndex = (VariantMetadataIndex) in.readObject();
			variantMetadataIndex.updateStorageDirectory(new File(metadataIndexPath));
			return variantMetadataIndex;
		} catch(Exception e) {
			// todo: handle exceptions better
			log.info("No Metadata Index found at " + metadataIndexPath);
			return null;
		}
	}

	public static void merge(VariantMetadataIndex variantMetadataIndex1, VariantMetadataIndex variantMetadataIndex2, String outputDirectory) throws IOException {
		VariantMetadataIndex merged = new VariantMetadataIndex(outputDirectory + VARIANT_METADATA_STORAGE_FILE_PREFIX);
		if (!variantMetadataIndex1.indexMap.keySet().equals(variantMetadataIndex2.indexMap.keySet())) {
			log.warn("Merging incompatible variant indexes. Index1 keys: " + Joiner.on(",").join(variantMetadataIndex1.indexMap.keySet()) + ". Index 2 keys: " + Joiner.on(",").join(variantMetadataIndex2.indexMap.keySet()));
			throw new IllegalStateException("Cannot merge variant metadata index with different contig keys");
		}
		for (String contig : variantMetadataIndex1.indexMap.keySet()) {
			String filePath = outputDirectory + VARIANT_METADATA_STORAGE_FILE_PREFIX + "_" + contig + ".bin";
			FileBackedByteIndexedStorage<Integer, ConcurrentHashMap<String, String[]>> mergedFbbis = new FileBackedJavaIndexedStorage(Integer.class, ConcurrentHashMap.class, new File(filePath));

			// Store the merged result here because FileBackedByteIndexedStorage must be written all at once
			Map<Integer, ConcurrentHashMap<String, String[]>> mergedStagedFbbis = new HashMap<>();

			FileBackedByteIndexedStorage<Integer, ConcurrentHashMap<String, String[]>> fbbis1 = variantMetadataIndex1.indexMap.get(contig);
			FileBackedByteIndexedStorage<Integer, ConcurrentHashMap<String, String[]>> fbbis2 = variantMetadataIndex2.indexMap.get(contig);

			fbbis1.keys().forEach(key -> {
				mergedStagedFbbis.put(key, fbbis1.get(key));
			});
			fbbis2.keys().forEach(key -> {
				ConcurrentHashMap<String, String[]> metadataMap = mergedStagedFbbis.get(key);
				if (metadataMap == null) {
					mergedStagedFbbis.put(key, fbbis2.get(key));
				} else {
					metadataMap.putAll(fbbis2.get(key));
				}
			});

			mergedStagedFbbis.forEach(mergedFbbis::put);
			mergedFbbis.complete();
			merged.indexMap.put(contig, mergedFbbis);
		}

		try(ObjectOutputStream out = new ObjectOutputStream(new GZIPOutputStream(Files.newOutputStream(new File(outputDirectory + VARIANT_METADATA_FILENAME).toPath())))){
			out.writeObject(merged);
			out.flush();
		}
	}

	public void updateStorageDirectory(File genomicDataDirectory) {
		indexMap.values().forEach(value -> value.updateStorageDirectory(genomicDataDirectory));
	}
}