package edu.harvard.hms.dbmi.avillach.hpds.data.genotype;

import java.io.File;
import java.io.IOException;
import java.io.Serializable;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import org.apache.log4j.Logger;

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
	public static String VARIANT_METADATA_BIN_FILE = "/opt/local/hpds/all/VariantMetadata.javabin";
	
	private static final long serialVersionUID = 5917054606643971537L;
	private static Logger log = Logger.getLogger(VariantMetadataIndex.class); 

	// (String) contig  --> (Integer) Bucket -->  (String) variant spec --> INFO column data[].
	private Map<String,  FileBackedByteIndexedStorage<Integer, ConcurrentHashMap<String, String[]>> > indexMap = new HashMap<String,  FileBackedByteIndexedStorage<Integer, ConcurrentHashMap<String, String[]>> >();
	private static String fileStoragePrefix = "/opt/local/hpds/all/VariantMetadataStorage";

	/**
	 * This map allows us to load millions of variants without re-writing the fbbis each time (which would blow up the disk space).
	 * We need to remember to flush() between each contig this gets saved to the fbbis array.
	 */
	private transient Map<String,  ConcurrentHashMap<Integer, ConcurrentHashMap<String, String[]>> > loadingMap = new HashMap<String,  ConcurrentHashMap<Integer, ConcurrentHashMap<String, String[]>> >();
	
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
	public String[] findBySingleVariantSpec(String variantSpec, VariantBucketHolder<String[]> bucketCache) {
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
					return new String[0];
				}
				bucketCache.lastValue = ContigFbbis.get(chrOffset);
				bucketCache.lastContig = contig;
				bucketCache.lastChunkOffset = chrOffset;
			}
			return bucketCache.lastValue != null ? bucketCache.lastValue.get(variantSpec) :  new String[0];
		} catch (IOException e) {
			log.warn("IOException caught looking up variantSpec : " + variantSpec, e);
			return new String[0];
		}
	}

	public Map<String, String[]> findByMultipleVariantSpec(Collection<String> varientSpecList) {
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
	 * @param array
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
			log.info("writing contig " + contig);
			String filePath = fileStoragePrefix + "_" + contig + ".bin";
			
			FileBackedByteIndexedStorage<Integer, ConcurrentHashMap<String, String[]>> ContigFbbis = new FileBackedByteIndexedStorage<Integer, ConcurrentHashMap<String, String[]>>(Integer.class, (Class<ConcurrentHashMap<String, String[]>>)(Class<?>) ConcurrentHashMap.class, new File(filePath));
			indexMap.put(contig, ContigFbbis);
			
			ConcurrentHashMap<Integer, ConcurrentHashMap<String, String[]>> contigMap = loadingMap.get(contig);
			for(Integer bucketNumber : contigMap.keySet()) {
				ContigFbbis.put(bucketNumber, contigMap.get(bucketNumber));
			}
			
			ContigFbbis.complete();
			log.info("Saved FBBIS for " + contig);
		}
		//now reset the map
		loadingMap = new HashMap<String,  ConcurrentHashMap<Integer, ConcurrentHashMap<String, String[]>> >();
	}
}