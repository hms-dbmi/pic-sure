package edu.harvard.hms.dbmi.avillach.hpds.data.genotype;

import java.io.*;
import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import edu.harvard.hms.dbmi.avillach.hpds.storage.FileBackedByteIndexedStorage;

public class BucketIndexBySample implements Serializable {
	private static final long serialVersionUID = -1230735595028630687L;
	private static final String STORAGE_FILE_NAME = "BucketIndexBySampleStorage.javabin";

	List<Integer> patientIds;
	ArrayList<String> contigSet;

	transient Logger log = LoggerFactory.getLogger(BucketIndexBySample.class);
	
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
		this(variantStore, "/opt/local/hpds/all/");
	}
	
	public BucketIndexBySample(VariantStore variantStore, String storageDir) throws FileNotFoundException {
		log.info("Creating new Bucket Index by Sample");
		final String storageFileStr = storageDir + STORAGE_FILE_NAME;
		
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
							//bucket index also has bookend bits
							int indexOfBucket = Collections.binarySearch(bucketList, bucketKey) + 2;
							
							// Create a bitmask with 1 values for each patient who has any variant in this bucket
							BigInteger[] patientMaskForBucket = {variantStore.emptyBitmask()};
							try {
								contigStore.get(bucket).values().forEach((VariantMasks masks)->{
									if(masks.heterozygousMask!=null) {
										patientMaskForBucket[0] = patientMaskForBucket[0].or(masks.heterozygousMask);
									}
									//add hetreo no call bits to mask
									if(masks.heterozygousNoCallMask!=null) {
										patientMaskForBucket[0] = patientMaskForBucket[0].or(masks.heterozygousNoCallMask);
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
							int maxIndex = patientMaskForBucket[0].bitLength() - 1;
							for(int x = 2;x<patientMaskForBucket[0].bitLength()-2;x++) {
								//testBit is uses inverted indexes
								if(patientMaskForBucket[0].testBit(maxIndex - x)) {
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
		
		// populate patientBucketMasks with bucketMasks for each patient 
		patientBucketMasks = new FileBackedByteIndexedStorage<Integer, BigInteger>(Integer.class, BigInteger.class, new File(storageFileStr));
		
		//the process to write out the bucket masks takes a very long time.  
		//Lets spin up another thread that occasionally logs progress
		int[] processedPatients = new int[1];
		processedPatients[0] = 0;
		new Thread(new Runnable() {
			@Override
			public void run() {
				log.info("writing patient bucket masks to backing store (this may take some time).");
				while(!patientBucketMasks.isComplete()) {
					try {
						Thread.sleep(5 * 1000 * 60); //log a message every 5 minutes
					} catch (InterruptedException e) {
						e.printStackTrace();
					}  
					log.info("wrote " + processedPatients[0] + " patient bucket masks");
				}
			}
		}).start();
		
		patientIds.parallelStream().forEach((patientId)->{
			try {
				BigInteger patientMask = new BigInteger(new String(patientBucketCharMasks[patientIds.indexOf(patientId)]),2);
				patientBucketMasks.put(patientId, patientMask);
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
		int maxIndex = bucketList.size() - 1; //use to invert testBit index
		return variantSet.parallelStream().filter((variantSpec)->{
			String bucketKey = variantSpec.split(",")[0] + ":" + (Integer.parseInt(variantSpec.split(",")[1])/1000);
			
			//testBit is least-significant-first order;  include +2 offset for bookends
			return _bucketMask.testBit(maxIndex - Collections.binarySearch(bucketList, bucketKey)  + 2);
		}).collect(Collectors.toSet());
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
	
	public void printPatientMasks() {
		for(Integer patientID : patientBucketMasks.keys()) {
			try {
				log.info("BucketMask length for " + patientID + ":\t" + patientBucketMasks.get(patientID).toString(2).length());
			} catch (IOException e) {
			log.error("FBBIS Error: ", e);
			}
		
	}
	}

}