package edu.harvard.hms.dbmi.avillach.hpds.data.genotype;

import java.io.*;
import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

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
		int bucketIndex = 0;
		
		for(String contig: contigSet) {
			log.info("Starting contig " + contig);
			int contigInteger = contigSet.indexOf(contig) * CONTIG_SCALE;
//			variantStore.variantMaskStorage.get(contig).keys().stream().forEach((bucket)->{
			FileBackedByteIndexedStorage<Integer, ConcurrentHashMap<String, VariantMasks>> contigStore = variantStore.variantMaskStorage.get(contig);
			
			if(contigStore == null || contigStore.keys() == null) {
				log.info("skipping contig " + contig);;
				continue;
			}
			
			for( Integer bucket : variantStore.variantMaskStorage.get(contig).keys()) {
				bucketIndexReference[bucketIndex] = contigInteger + bucket;
				
				//this is dumb, but we can't even reference non-global variables inside a lambda
				// something about autoboxing primatives on the stack
				// https://www.quora.com/Can-Java-Lambda-access-variables-outside-the-Lambda-function-What-it-be-done-by-adding-something-to-an-outside-list-or-by-a-lookup-on-a-map
				final int workAroundIt = bucketIndex;
				try {
					variantStore.variantMaskStorage.get(contig).get(bucket).forEach((variantSpec, masks)->{
						
						if( (variantCount++) % 50000 == 0) {
							log.info(Thread.currentThread().getName() +   " Processed " + variantCount + " variants");
						}
						
						BigInteger mask =
								(masks.homozygousMask == null ? emptyBitmask : masks.homozygousMask).or(
										(masks.heterozygousMask == null ? emptyBitmask : masks.heterozygousMask));
						
						
						patientIds.parallelStream().forEach((id)->{
							if(mask.testBit(patientIdIndex.get(id))) {
								//now flip ONE BIT
								int bucketByteIndex = workAroundIt / 8;
								int offset = workAroundIt % 8;
								index.get(id)[bucketByteIndex] |= bitMasks[offset];
							}
						});

					});
				} catch (IOException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
				bucketIndex++;
			}
		}
		
		log.info("Completed after " + variantCount + " variants");
		fbbis.open();
		//could parallelize this
		index.forEach((patientId, patientBucketArr)->{
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
	
	
	
//	
//	/**y point for piecemeal caching of patient->contig mappings
//	 * ENtr
//	 * @param args
//	 * @throws ClassNotFoundException
//	 * @throws FileNotFoundException
//	 * @throws IOException
//	 */
//	public static void main(String[] args) throws ClassNotFoundException, FileNotFoundException, IOException {
//		
//		if(args[0] == null || args[0].isEmpty()) {
//			System.out.println("No contig selected");
//			return;
//		} else {
//			System.out.println("Caching contig " + args[0]);
//		}
//		
//		
//		if(new File("/opt/local/hpds/all/variantStore.javabin").exists()) {
//			ObjectInputStream stream = new ObjectInputStream(new GZIPInputStream(new FileInputStream("/opt/local/hpds/all/variantStore.javabin")));
//			
//			VariantStore variantStore = (VariantStore) stream.readObject();
//			stream.close();
//			variantStore.open();	
//		
//			BucketIndexBySample bucketIndex = new BucketIndexBySample(variantStore, args[0]);
//			try (
//					FileOutputStream fos = new FileOutputStream(BucketIndexBySample.INDEX_FILE);
//					GZIPOutputStream gzos = new GZIPOutputStream(fos);
//					ObjectOutputStream oos = new ObjectOutputStream(gzos);			
//					){
//				oos.writeObject(bucketIndex);
//				oos.flush();oos.close();
//			}
//		}
//	}
	
	
	
	
	

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