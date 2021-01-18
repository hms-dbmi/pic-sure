package edu.harvard.hms.dbmi.avillach.hpds.data.genotype;

import java.io.*;
import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;

import org.apache.log4j.Logger;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.Sets;

import edu.harvard.hms.dbmi.avillach.hpds.storage.FileBackedByteIndexedStorage;

public class BucketIndexBySample implements Serializable {
	private static final long serialVersionUID = -1230735595028630687L;
	public static final String INDEX_FILE = "/opt/local/hpds/all/BucketIndexBySample.javabin";
	private static final String STORAGE_FILE = "/opt/local/hpds/all/BucketIndexBySampleStorage.javabin";
	private static final int CONTIG_SCALE = 1000000;
	
	//patient ID -> hash set contains bucket identifier
	
	//bucket is a region of 1000 base pairs
	private FileBackedByteIndexedStorage<Integer, HashSet<Integer>> fbbis;  

	List<Integer> patientIds;
	ArrayList<String> contigSet;
	
	private Integer variantCount;
	
	//used to track buckets while building the cache in a stream
	private Integer bucketIndex;

	transient Logger log = Logger.getLogger(BucketIndexBySample.class);
	
	//here's an array of bytes with a single bit set; these are used for masks.
	//the bit set is the index in the array
	final static byte[] bitMasks = {(byte)0x01, (byte)0x02, (byte)0x04, (byte)0x08, (byte)0x10, (byte)0x20, (byte)0x40, (byte)0x80};
	
	
	public BucketIndexBySample(VariantStore variantStore) throws FileNotFoundException {
		log.info("Creating new Bucket Index by Sample");
		fbbis = new FileBackedByteIndexedStorage(Integer.class, HashSet.class, new File(STORAGE_FILE));
		HashMap<Integer,byte[]> index = new HashMap<Integer, byte[]>();
		contigSet = new ArrayList<String>(variantStore.variantMaskStorage.keySet());
		if(contigSet.size() > 2000) {
			throw new RuntimeException("Too many contigs");
		}
		
		int bucketCount = 0;
		for(String contig: contigSet){
			FileBackedByteIndexedStorage<Integer, ConcurrentHashMap<String, VariantMasks>> contigStore = variantStore.variantMaskStorage.get(contig);
			if(contigStore != null && contigStore.keys() != null) {
				bucketCount += contigStore.keys().size();
				log.info("Found " + variantStore.variantMaskStorage.get(contig).keys().size() + " buckets in contig " + contig);
			} else {
				log.info("null entry for contig " + contig);
			}
		}
		
		log.info("Found " + bucketCount + " total buckets");
		
		patientIds = Arrays.stream(variantStore.getPatientIds()).map(id->{return Integer.parseInt(id);}).collect(Collectors.toList());
		HashMap<Integer, Integer> patientIdIndex = new HashMap<>(patientIds.size());
		
		
		for(int patientId : patientIds) {
			// NC - turns out each char is 2 bytes!  need to shrink that down.
			
			//one byte now stores 8 buckets; need one at the end so we don't miss the remainder
			byte[] patientBucketArr = new byte[bucketCount/8 + 1];
			Arrays.fill(patientBucketArr, (byte)0x00);
			
			index.put(patientId, patientBucketArr);
			//add the offset here, instead of every time it's looked up
			patientIdIndex.put(patientId, patientIds.indexOf(patientId) + 2);
		}
		
		int[] bucketIndexReference = new int[bucketCount];
		
		BigInteger emptyBitmask = variantStore.emptyBitmask();
		variantCount = 0;
		bucketIndex = 0;
		
		contigSet.parallelStream().forEach( contig -> {
			log.info("Starting contig " + contig);
			int contigInteger = contigSet.indexOf(contig) * CONTIG_SCALE;
			FileBackedByteIndexedStorage<Integer, ConcurrentHashMap<String, VariantMasks>> contigStore = variantStore.variantMaskStorage.get(contig);
			
			if(contigStore == null || contigStore.keys() == null) {
				log.info("skipping contig " + contig);;
				return;
			}
			
			for( Integer bucket : variantStore.variantMaskStorage.get(contig).keys()) {
				final int threadBucketIndex = bucketIndex;
				bucketIndex++;
				
				bucketIndexReference[threadBucketIndex] = contigInteger + bucket;
				
				try {
					variantStore.variantMaskStorage.get(contig).get(bucket).forEach((variantSpec, masks)->{
						if( (variantCount++) % 100000 == 0) {
							log.info(Thread.currentThread().getName() +   " Processed " + variantCount + " variants");
						}
						
						BigInteger mask =
								(masks.homozygousMask == null ? emptyBitmask : masks.homozygousMask).or(
										(masks.heterozygousMask == null ? emptyBitmask : masks.heterozygousMask));
						
						patientIds.forEach((id)->{
							if(mask.testBit(patientIdIndex.get(id))) {
								//now flip ONE BIT
								int bucketByteIndex = threadBucketIndex / 8;
								int offset = threadBucketIndex % 8;
								index.get(id)[bucketByteIndex] |= bitMasks[offset];
							}
						});

					});
				} catch (IOException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
			}
		});
		
		log.info("Completed after " + variantCount + " variants");
		fbbis.open();
		index.entrySet().parallelStream().forEach( entry -> {
			Integer patientId = entry.getKey();
			byte[] patientBucketArr = entry.getValue();
			try {
				HashSet<Integer> patientBucketSet = new HashSet<Integer>(patientBucketArr.length);
				for(int i = 0; i < patientBucketArr.length; i++){
					for(int j = 0; j < 8 ; j++) {
						
						if((patientBucketArr[i] & bitMasks[j]) != 0) {
							patientBucketSet.add(bucketIndexReference[(i * 8) + j]);
						}
					}
				}
				fbbis.put(patientId, patientBucketSet);
			} catch (IOException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		});
		fbbis.complete();
	}
	
	public Collection<String> filterVariantSetForPatientSet(Set<String> variantSet, List<Integer> patientSet){
		
		// Build a list of buckets represented in the variantSet
		ConcurrentHashMap<Integer,ConcurrentSkipListSet<String>> bucketsFromVariants = new ConcurrentHashMap<Integer, ConcurrentSkipListSet<String>>();
		variantSet.parallelStream().forEach((String variantSpec)->{
			int bucket = bucketFromVariantSpec(variantSpec);
			ConcurrentSkipListSet<String> variantsInBucket = bucketsFromVariants.get(bucket);
			if(variantsInBucket == null) {
				variantsInBucket = new ConcurrentSkipListSet<String>();
				bucketsFromVariants.put(bucket, variantsInBucket);
			}
			variantsInBucket.add(variantSpec);
		});
		Set<Integer> variantBucketSet = bucketsFromVariants.keySet();
		Set<Integer>[]  bucketSetsInScope = new Set[] {variantBucketSet};
		
		patientSet.parallelStream().map((patientId)->{return getBucketSetForPatientId(patientId);}).forEach((bucketSet)->{
			Set patientBucketsInVariantBuckets = Sets.intersection(variantBucketSet, bucketSet);
			synchronized (bucketSetsInScope) {
				bucketSetsInScope[0] = Sets.union(bucketSetsInScope[0], patientBucketsInVariantBuckets);
			}
		});;
		
		return bucketSetsInScope[0].parallelStream().map((bucketSet)->{
			return bucketsFromVariants.get(bucketSet);
		}).flatMap((bucketSet)->{return bucketSet.stream();}).collect(Collectors.toList());
	}

	private Integer bucketFromVariantSpec(String variantSpec) {
		String[] contig_and_offset = new String[2];
		int start = 0;
		int current = 0;
		for(int x = 0;contig_and_offset[1]==null;x++) {
			if(variantSpec.charAt(x)==',') {
				contig_and_offset[current++] = variantSpec.substring(start,x-1);
				start = x+1;
			}
		}
		return (contigSet.indexOf(contig_and_offset[0]) * CONTIG_SCALE) + (Integer.parseInt(contig_and_offset[1])/1000);
	}


	transient LoadingCache<Integer, HashSet<Integer>> bucketSetCache = CacheBuilder.newBuilder()
			.maximumSize(1000).build(new CacheLoader<Integer, HashSet<Integer>>() {
				@Override
				public HashSet<Integer> load(Integer patientId) throws Exception {
					return  fbbis.get(patientId);
				}
			});

	private HashSet<Integer> getBucketSetForPatientId(Integer patientId) {
		//  THIS IS TEMPORARY
		if(bucketSetCache==null) {
			bucketSetCache = CacheBuilder.newBuilder()
					.maximumSize(1000).build(new CacheLoader<Integer, HashSet<Integer>>() {
						@Override
						public HashSet<Integer> load(Integer patientId) throws Exception {
							return  fbbis.get(patientId);
						}
					});
		}
		try {
			return  bucketSetCache.get(patientId);
		} catch (ExecutionException e) {
			log.error(e);
		}
		return new HashSet<Integer>();
	}

}