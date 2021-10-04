package edu.harvard.hms.dbmi.avillach.hpds.data.genotype;

import java.io.*;
import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import org.apache.log4j.Logger;

import edu.harvard.hms.dbmi.avillach.hpds.storage.FileBackedByteIndexedStorage;

public class BucketIndexBySample implements Serializable {
	private static final long serialVersionUID = -1230735595028630687L;
	public static final String INDEX_FILE = "/opt/local/hpds/all/BucketIndexBySample.javabin";
	private static final String STORAGE_FILE = "/opt/local/hpds/all/BucketIndexBySampleStorage.javabin";

	List<Integer> patientIds;
	ArrayList<String> contigSet;

	transient Logger log = Logger.getLogger(BucketIndexBySample.class);
	
	/**
	 * Threadsafe Map of patientNum to a BigInteger which acts as a bitmask of the buckets in which each patient
	 * has a variant. The bits in the BigInteger are indexed by the bucketList. To find the offset of a bucket's
	 * bit in the mask use patientBucketMask.get(patientId).testBit(Collections
	 */
	private FileBackedByteIndexedStorage<Integer, BigInteger> patientBucketMasks;
	
	/**
	 * ArrayList containing all bucket keys in the dataset used as an index for the patientBucketMask offsets.
	 * This list is in natural sort order so Collections.binarySearch should be used instead of indexOf when 
	 * finding the offset of a given bucket.
	 */
	private ArrayList<String> bucketList = new ArrayList<String>();
	
	public BucketIndexBySample(VariantStore variantStore) throws FileNotFoundException {
		log.info("Creating new Bucket Index by Sample");
		contigSet = new ArrayList<String>(variantStore.variantMaskStorage.keySet());
		
		//Create a bucketList, containing keys for all buckets in the variantStore
		for(String contig: contigSet){
			FileBackedByteIndexedStorage<Integer, ConcurrentHashMap<String, VariantMasks>> contigStore = variantStore.variantMaskStorage.get(contig);
			if(contigStore != null && contigStore.keys() != null) {
				bucketList.addAll(contigStore.keys().stream().map(
						(Integer bucket)->{
							return contig  +  ":" + bucket;
						}).collect(Collectors.toList()));
				log.info("Found " + contigStore.keys().size() + " buckets in contig " + contig);
			} else {
				log.info("null entry for contig " + contig);
			}
		}

		// bucketList must be sorted so we can later use binarySearch to find offsets for specific buckets
		// in the patientBucketMask records
		Collections.sort(bucketList);
		
		log.info("Found " + bucketList.size() + " total buckets");
		
		// get all patientIds as Integers, eventually this should be fixed in variantStore so they are
		// Integers to begin with, which would mean reloading all variant data everywhere so that will
		// have to wait.
		patientIds = Arrays.stream(variantStore.getPatientIds()).map(id->{
			return Integer.parseInt(id);
			}).collect(Collectors.toList());
		
		// create empty char arrays for each patient 
		char[][] patientBucketCharMasks = new char[patientIds.size()][bucketList.size()];
		for(int x = 0;x<patientBucketCharMasks.length;x++) {
			patientBucketCharMasks[x] = emptyBucketMaskChar();
		}
		contigSet.parallelStream().forEach((contig)->{
			FileBackedByteIndexedStorage<Integer, ConcurrentHashMap<String, VariantMasks>> contigStore =
					variantStore.variantMaskStorage.get(contig);
			if(contigStore != null && contigStore.keys() != null) {
				contigStore.keys().stream().forEach(
						(Integer bucket)->{
							String bucketKey = contig  +  ":" + bucket;
							int indexOfBucket = Collections.binarySearch(bucketList, bucketKey);
							
							// Create a bitmask with 1 values for each patient who has any variant in this bucket
							BigInteger[] patientMaskForBucket = {variantStore.emptyBitmask()};
							try {
								contigStore.get(bucket).values().forEach((VariantMasks masks)->{
									if(masks.heterozygousMask!=null) {
										patientMaskForBucket[0] = patientMaskForBucket[0].or(masks.heterozygousMask);
									}
									if(masks.homozygousMask!=null) {
										patientMaskForBucket[0] = patientMaskForBucket[0].or(masks.homozygousMask);
									}
								});
							} catch (IOException e) {
								e.printStackTrace();
							}
							// For each patient set the patientBucketCharMask entry to '1' if they have a variant
							// in this bucket, or '0' if they dont
							for(int x = 2;x<patientMaskForBucket[0].bitLength()-2;x++) {
								if(patientMaskForBucket[0].testBit(x)) {
									patientBucketCharMasks[x-2][indexOfBucket] = '1';									
								}else {
									patientBucketCharMasks[x-2][indexOfBucket] = '0';
								}
							}
						});
			} else {
				log.info("null entry for contig " + contig);
			}
			log.info("completed contig " + contig);
		});
		
		//the process to populate the bucket masks takes a very long time.  
		//Lets spin up another thread that occasionally logs progress
		int[] processedPatients = new int[1];
		processedPatients[0] = 0;
		new Thread(new Runnable() {
			@Override
			public void run() {
				while(!patientBucketMasks.isComplete()) {
					try {
						Thread.sleep(3 * 1000 * 60); //log a message every 3 minutes
					} catch (InterruptedException e) {
						e.printStackTrace();
					}  
					log.info("processed " + processedPatients[0] + " patient bucket masks");
				}
			}
		}).start();
		
		// populate patientBucketMasks with bucketMasks for each patient 
		patientBucketMasks = new FileBackedByteIndexedStorage<Integer, BigInteger>(Integer.class, BigInteger.class, new File(STORAGE_FILE));
		patientIds.parallelStream().forEach((patientId)->{
			try {
				BigInteger patientMask = new BigInteger(new String(patientBucketCharMasks[patientIds.indexOf(patientId)]),2);
				patientBucketMasks.put(patientId,
					patientMask);
			}catch(NumberFormatException e) {
				log.error("NFE caught for " + patientId, e);
			} catch (IOException e) {
				e.printStackTrace();
			}
			processedPatients[0] += 1;
		});
		patientBucketMasks.complete();
		log.info("Done creating patient bucket masks");
	}
	
	/**
	 * Given a set of variants and a set of patientNums, filter out variants which no patients in the patientSet
	 * have any variant in the bucket. This operation is extremely fast and cuts down on processing by excluding
	 * variants for queries where not all patients are included.
	 * 
	 * @param variantSet
	 * @param patientSet
	 * @return
	 * @throws IOException 
	 */
	public Collection<String> filterVariantSetForPatientSet(Set<String> variantSet, List<Integer> patientSet) throws IOException{
		
		
		//a bitmask of which buckets contain any relevant variant. 
		BigInteger patientBucketMask = patientSet.size() == 0 ? 
				new BigInteger(new String(emptyBucketMaskChar()),2) : patientBucketMasks.get(patientSet.get(0));
	
		BigInteger _defaultMask = patientBucketMask;
		List<BigInteger> patientBucketmasksForSet = patientSet.parallelStream().map((patientNum)->{
			try {
				return patientBucketMasks.get(patientNum);
			} catch (IOException e) {
				e.printStackTrace();
			}
			return _defaultMask;
		}).collect(Collectors.toList());
		for(BigInteger patientMask : patientBucketmasksForSet) {
			patientBucketMask = patientMask.or(patientBucketMask);
		}
		
		BigInteger _bucketMask = patientBucketMask;
		return variantSet.parallelStream().filter((variantSpec)->{
			String bucketKey = variantSpec.split(",")[0] + ":" + (Integer.parseInt(variantSpec.split(",")[1])/1000);
			return _bucketMask.testBit(findOffsetOfBucket(bucketKey));
		}).collect(Collectors.toSet());
	}

	/**
	 * Convenience method to map a bucketKey to the offset of it's corresponding bit in a patientBucketMask
	 * 
	 * @param bucketKey
	 * @return offset of bit in patientBucketMask corresponding to the bucketKey
	 */
	private int findOffsetOfBucket(String bucketKey) {
		return (bucketList.size()+4) - Collections.binarySearch(bucketList, bucketKey) - 2;
	}

	private char[] _emptyBucketMaskChar = null;

	/**
	 * Produce an empty patientBucketMask char[] by cloning a momoized empty patientBucketMask after the
	 * first has been created.
	 * 
	 * @return
	 */
	private char[] emptyBucketMaskChar() {
		if(_emptyBucketMaskChar == null) {
			char[] bucketMaskChar = new char[bucketList.size()+4];
			bucketMaskChar[0] = '1';
			bucketMaskChar[1]  = '1'; 
			bucketMaskChar[bucketMaskChar.length-1] = '1';
			bucketMaskChar[bucketMaskChar.length-2]='1';
			for(int  x = 2;x<bucketMaskChar.length-2;x++) {
				bucketMaskChar[x] = '0';
			}
			_emptyBucketMaskChar = bucketMaskChar;
		}
		return _emptyBucketMaskChar.clone();
	}

}