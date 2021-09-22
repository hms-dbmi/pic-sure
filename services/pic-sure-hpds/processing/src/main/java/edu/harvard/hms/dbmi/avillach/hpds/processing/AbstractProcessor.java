package edu.harvard.hms.dbmi.avillach.hpds.processing;

import java.io.*;
import java.math.BigInteger;
import java.util.*;
import java.util.Map.Entry;
import java.util.concurrent.*;
import java.util.stream.Collectors;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import org.apache.log4j.Level;
//import org.apache.commons.math3.stat.inference.ChiSquareTest;
//import org.apache.commons.math3.stat.inference.TTest;
import org.apache.log4j.Logger;

import com.google.common.cache.*;
import com.google.common.collect.Lists;
import com.google.common.collect.Range;
import com.google.common.collect.Sets;

import edu.harvard.hms.dbmi.avillach.hpds.crypto.Crypto;
import edu.harvard.hms.dbmi.avillach.hpds.data.genotype.*;
import edu.harvard.hms.dbmi.avillach.hpds.data.genotype.caching.VariantBucketHolder;
import edu.harvard.hms.dbmi.avillach.hpds.data.phenotype.ColumnMeta;
import edu.harvard.hms.dbmi.avillach.hpds.data.phenotype.PhenoCube;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.Filter.DoubleFilter;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.Filter.FloatFilter;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.Query;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.Query.VariantInfoFilter;
import edu.harvard.hms.dbmi.avillach.hpds.exception.NotEnoughMemoryException;
import edu.harvard.hms.dbmi.avillach.hpds.storage.FileBackedByteIndexedStorage;

public abstract class AbstractProcessor {

	private static boolean dataFilesLoaded = false;
	private static BucketIndexBySample bucketIndex;
	private static final Integer VARIANT_INDEX_BLOCK_SIZE = 1000000;
	private static final String VARIANT_INDEX_FBBIS_STORAGE_FILE = "/opt/local/hpds/all/variantIndex_fbbis_storage.javabin";
	private static final String VARIANT_INDEX_FBBIS_FILE = "/opt/local/hpds/all/variantIndex_fbbis.javabin";

	public AbstractProcessor() throws ClassNotFoundException, FileNotFoundException, IOException {
		store = initializeCache(); 
		synchronized(store) {
			Object[] metadata = loadMetadata();
			metaStore = (TreeMap<String, ColumnMeta>) metadata[0];
			allIds = (TreeSet<Integer>) metadata[1];
			loadAllDataFiles();
			infoStoreColumns = new ArrayList<String>(infoStores.keySet());
		}
	}



	/**
	 * This process takes a while (even after the cache is built), so let's spin it out into it's own thread. (not done yet)
	 * @throws FileNotFoundException
	 * @throws IOException
	 * @throws InterruptedException
	 */
	private synchronized void loadGenomicCacheFiles() throws FileNotFoundException, IOException, InterruptedException {
		//skip if we have no variants
		if(variantStore.getPatientIds().length == 0) {
			variantIndex = new String[0];
			log.warn("No Genomic Data found.  Skipping variant Indexing");
			return;
		}

		if(bucketIndex==null) {
			if(variantIndex==null) {
				if(!new File(VARIANT_INDEX_FBBIS_FILE).exists()) {
					log.info("Creating new " + VARIANT_INDEX_FBBIS_FILE);
					populateVariantIndex();
					FileBackedByteIndexedStorage<Integer, String[]> fbbis = 
							new FileBackedByteIndexedStorage<Integer, String[]>(Integer.class, String[].class, new File(VARIANT_INDEX_FBBIS_STORAGE_FILE));
							try (ObjectOutputStream oos = new ObjectOutputStream(new GZIPOutputStream(new FileOutputStream(VARIANT_INDEX_FBBIS_FILE)));
									){

								log.info("Writing Cache Object in blocks of " + VARIANT_INDEX_BLOCK_SIZE);

								int bucketCount = (variantIndex.length / VARIANT_INDEX_BLOCK_SIZE) + 1;  //need to handle overflow
								int index = 0;
								for( int i = 0; i < bucketCount; i++) {
									int blockSize = i == (bucketCount - 1) ? (variantIndex.length % VARIANT_INDEX_BLOCK_SIZE) : VARIANT_INDEX_BLOCK_SIZE; 

									String[] variantArrayBlock = new String[blockSize];
									System.arraycopy(variantIndex, index, variantArrayBlock, 0, blockSize);
									fbbis.put(i, variantArrayBlock);

									index += blockSize;
									log.info("saved " + index + " variants");
								}
								fbbis.complete();
								oos.writeObject("" + variantIndex.length);
								oos.writeObject(fbbis);
								oos.flush();oos.close();
							}
				}else {
					ExecutorService ex = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());
					try (ObjectInputStream objectInputStream = new ObjectInputStream(new GZIPInputStream(new FileInputStream(VARIANT_INDEX_FBBIS_FILE)));){
						Integer variantCount = Integer.parseInt((String) objectInputStream.readObject());
						FileBackedByteIndexedStorage<Integer, String[]> indexStore = (FileBackedByteIndexedStorage<Integer, String[]>) objectInputStream.readObject();
						log.info("loading " + VARIANT_INDEX_FBBIS_FILE);

						variantIndex = new String[variantCount];
						String[] _varaiantIndex2 = variantIndex;

						//variant index has to be a single array (we use a binary search for lookups)
						//but reading/writing to disk should be batched for performance
						int bucketCount = (variantCount / VARIANT_INDEX_BLOCK_SIZE) + 1;  //need to handle overflow

						for( int i = 0; i < bucketCount; i++) {
							final int _i = i;
							ex.submit(new Runnable() {
								@Override
								public void run() {
									try {
										String[] variantIndexBucket = indexStore.get(_i);
										System.arraycopy(variantIndexBucket, 0, _varaiantIndex2, (_i * VARIANT_INDEX_BLOCK_SIZE), variantIndexBucket.length);
										log.info("loaded " + (_i * VARIANT_INDEX_BLOCK_SIZE) + " block");
									} catch (IOException e) {
										// TODO Auto-generated catch block
										e.printStackTrace();
									}
								}
							});
						}
						objectInputStream.close();
						ex.shutdown();
						while(! ex.awaitTermination(60, TimeUnit.SECONDS)) {
							System.out.println("Waiting for tasks to complete");
							Thread.sleep(10000);
						}
					} catch (IOException | ClassNotFoundException | NumberFormatException e) {
						log.error(e);
					}
					log.info("Found " + variantIndex.length + " total variants.");
				}
			}
			if(variantStore.getPatientIds().length > 0 && !new File(BucketIndexBySample.INDEX_FILE).exists()) {
				log.info("creating new " + BucketIndexBySample.INDEX_FILE);
				bucketIndex = new BucketIndexBySample(variantStore);
				try (
						FileOutputStream fos = new FileOutputStream(BucketIndexBySample.INDEX_FILE);
						GZIPOutputStream gzos = new GZIPOutputStream(fos);
						ObjectOutputStream oos = new ObjectOutputStream(gzos);			
						){
					oos.writeObject(bucketIndex);
					oos.flush();oos.close();
				}
			}else {
				try (ObjectInputStream objectInputStream = new ObjectInputStream(new GZIPInputStream(new FileInputStream(BucketIndexBySample.INDEX_FILE)));){
					log.info("loading " + BucketIndexBySample.INDEX_FILE);
					bucketIndex = (BucketIndexBySample) objectInputStream.readObject();
					objectInputStream.close();
				} catch (IOException | ClassNotFoundException e) {
					log.error(e);
				} 
			}
		}
	}

	public AbstractProcessor(boolean isOnlyForTests) throws ClassNotFoundException, FileNotFoundException, IOException  {
		if(!isOnlyForTests) {
			throw new IllegalArgumentException("This constructor should never be used outside tests");
		}
	}

	private static final String HOMOZYGOUS_VARIANT = "1/1";

	private static final String HETEROZYGOUS_VARIANT = "0/1";

	private static final String HOMOZYGOUS_REFERENCE = "0/0";

	private static Logger log = Logger.getLogger(AbstractProcessor.class);

	protected static String ID_CUBE_NAME;

	static {
		CACHE_SIZE = Integer.parseInt(System.getProperty("CACHE_SIZE", "100"));
		ID_BATCH_SIZE = Integer.parseInt(System.getProperty("ID_BATCH_SIZE", "0"));
		ID_CUBE_NAME = System.getProperty("ID_CUBE_NAME", "NONE");
	}

	protected static int ID_BATCH_SIZE;

	protected static int CACHE_SIZE;

	public static List<String> infoStoreColumns;

	protected static HashMap<String, FileBackedByteIndexedInfoStore> infoStores;

	protected static LoadingCache<String, PhenoCube<?>> store;

	//variantStore will never be null; it is initialized to an empty object.
	protected static VariantStore variantStore;

	protected static TreeMap<String, ColumnMeta> metaStore;

	protected TreeSet<Integer> allIds;

	protected Object[] loadMetadata() {
		try (ObjectInputStream objectInputStream = new ObjectInputStream(new GZIPInputStream(new FileInputStream("/opt/local/hpds/columnMeta.javabin")));){
			TreeMap<String, ColumnMeta> metastore = (TreeMap<String, ColumnMeta>) objectInputStream.readObject();
			TreeMap<String, ColumnMeta> metastoreScrubbed = new TreeMap<String, ColumnMeta>();
			for(Entry<String,ColumnMeta> entry : metastore.entrySet()) {
				metastoreScrubbed.put(entry.getKey().replaceAll("\\ufffd",""), entry.getValue());
			}
			Set<Integer> allIds = (TreeSet<Integer>) objectInputStream.readObject();
			objectInputStream.close();
			return new Object[] {metastoreScrubbed, allIds};
		} catch (IOException | ClassNotFoundException e) {
			e.printStackTrace();
			log.warn("************************************************");
			log.warn("************************************************");
			log.warn("Could not load metastore");
			log.warn("If you meant to include phenotype data of any kind, please check that the file /opt/local/hpds/columnMeta.javabin exists and is readable by the service.");
			log.warn("************************************************");
			log.warn("************************************************");
			return new Object[] {new TreeMap<String, ColumnMeta>(), new TreeSet<Integer>()};
		} 
	}

	/**
	 * Merges a list of sets of patient ids by intersection. If we implemented OR semantics
	 * this would be where the change happens.
	 * 
	 * @param filteredIdSets
	 * @return
	 */
	protected Set<Integer> applyBooleanLogic(ArrayList<Set<Integer>> filteredIdSets) {
		Set<Integer>[] ids = new Set[] {filteredIdSets.get(0)};
		filteredIdSets.forEach((keySet)->{
			ids[0] = Sets.intersection(ids[0], keySet);
		});
		return ids[0];
	}
	//
	//	protected Map<String, Double> variantsOfInterestForSubset(String geneName, BigInteger caseMask, double pValueCutoff) throws IOException{
	//		TreeSet<String> nonsynonymous_SNVs = new TreeSet<>(Arrays.asList(infoStores.get("UCG").allValues.get("nonsynonymous_SNV")));
	//		TreeSet<String> variantsInGene = new TreeSet<>(Arrays.asList(infoStores.get("GN").allValues.get(geneName)));
	//		TreeSet<String> nonsynVariantsInGene = new TreeSet<String>(Sets.intersection(variantsInGene, nonsynonymous_SNVs));
	//
	//		HashMap<String, Double> interestingVariants = new HashMap<>();
	//
	//		nonsynVariantsInGene.stream().forEach((variantSpec)->{
	//			VariantMasks masks;
	//			try {
	//				masks = variantStore.getMasks(variantSpec);
	//			} catch (IOException e) {
	//				throw new RuntimeException(e);
	//			}
	//			BigInteger controlMask = flipMask(caseMask);
	//			BigInteger variantAlleleMask = masks.heterozygousMask.or(masks.homozygousMask);
	//			BigInteger referenceAlleleMask = flipMask(variantAlleleMask);
	//			Double value = new ChiSquareTest().chiSquare(new long[][] {
	//				{variantAlleleMask.and(caseMask).bitCount()-4, variantAlleleMask.and(controlMask).bitCount()-4},
	//				{referenceAlleleMask.and(caseMask).bitCount()-4, referenceAlleleMask.and(controlMask).bitCount()-4}
	//			});
	//			if(value < pValueCutoff) {
	//				interestingVariants.put(variantSpec, value);
	//			}
	//		});
	//		return interestingVariants;
	//	}
//
//	/**
//	 * Returns a new BigInteger object where each bit except the bookend bits for the bitmask parameter have been flipped.
//	 * @param bitmask
//	 * @return
//	 */
//	private BigInteger flipMask(BigInteger bitmask) {
//		for(int x = 2;x<bitmask.bitLength()-2;x++) {
//			bitmask = bitmask.flipBit(x);
//		}
//		return bitmask;
//	}

	/**
	 * For each filter in the query, return a set of patient ids that match. The order of these sets in the
	 * returned list of sets does not matter and cannot currently be tied back to the filter that generated
	 * it.
	 * 
	 * @param query
	 * @return
	 */
	protected ArrayList<Set<Integer>> idSetsForEachFilter(Query query) {
		ArrayList<Set<Integer>> filteredIdSets = new ArrayList<Set<Integer>>();

		addIdSetsForAnyRecordOf(query, filteredIdSets);

		addIdSetsForRequiredFields(query, filteredIdSets);

		addIdSetsForNumericFilters(query, filteredIdSets);

		addIdSetsForCategoryFilters(query, filteredIdSets);

		if(filteredIdSets.size()>1) {
			filteredIdSets = new ArrayList<Set<Integer>>(List.of(applyBooleanLogic(filteredIdSets)));
		}

		addIdSetsForVariantInfoFilters(query, filteredIdSets);

		return filteredIdSets;
	}

	/**
	 * Process each filter in the query and return a list of patient ids that should be included in the
	 * result. 
	 * 
	 * @param query
	 * @return
	 */
	protected TreeSet<Integer> getPatientSubsetForQuery(Query query) {
		ArrayList<Set<Integer>> filteredIdSets;

		filteredIdSets = idSetsForEachFilter(query);

		TreeSet<Integer> idList;
		if(filteredIdSets.isEmpty()) {
			if(variantStore.getPatientIds().length > 0 ) {
				idList = new TreeSet(
						Sets.union(allIds, 
								new TreeSet(Arrays.asList(
										variantStore.getPatientIds()).stream()
										.collect(Collectors.mapping(
												(String id)->{return Integer.parseInt(id.trim());}, Collectors.toList()))) ));				
			}else {
				idList = allIds;
			}
		}else {
			idList = new TreeSet<Integer>(applyBooleanLogic(filteredIdSets));
		}
		return idList;
	}

	private void addIdSetsForRequiredFields(Query query, ArrayList<Set<Integer>> filteredIdSets) {
		if(query.requiredFields != null && !query.requiredFields.isEmpty()) {
			VariantBucketHolder<VariantMasks> bucketCache = new VariantBucketHolder<VariantMasks>();
			filteredIdSets.addAll((Set<TreeSet<Integer>>)(query.requiredFields.parallelStream().map(path->{
				if(pathIsVariantSpec(path)) {
					TreeSet<Integer> patientsInScope = new TreeSet<Integer>();
					addIdSetsForVariantSpecCategoryFilters(new String[]{"0/1","1/1"}, path, patientsInScope, bucketCache);
					return patientsInScope;
				} else {
					return new TreeSet<Integer>(getCube(path).keyBasedIndex());
				}
			}).collect(Collectors.toSet()))); 
		}
	}

	private void addIdSetsForAnyRecordOf(Query query, ArrayList<Set<Integer>> filteredIdSets) {
		if(query.anyRecordOf != null && !query.anyRecordOf.isEmpty()) {
			Set<Integer> patientsInScope = new ConcurrentSkipListSet<Integer>();
			VariantBucketHolder<VariantMasks> bucketCache = new VariantBucketHolder<VariantMasks>();
			query.anyRecordOf.parallelStream().forEach(path->{
				if(patientsInScope.size()<Math.max(
						allIds.size(),
						variantStore.getPatientIds().length)) {
					if(pathIsVariantSpec(path)) {
						addIdSetsForVariantSpecCategoryFilters(new String[]{"0/1","1/1"}, path, patientsInScope, bucketCache);
					} else {
						patientsInScope.addAll(getCube(path).keyBasedIndex());
					}
				}
			});
			filteredIdSets.add(patientsInScope);
		}
	}

	private void addIdSetsForNumericFilters(Query query, ArrayList<Set<Integer>> filteredIdSets) {
		if(query.numericFilters != null && !query.numericFilters.isEmpty()) {
			filteredIdSets.addAll((Set<TreeSet<Integer>>)(query.numericFilters.keySet().parallelStream().map((String key)->{
				DoubleFilter doubleFilter = query.numericFilters.get(key);
				return (TreeSet<Integer>)(getCube(key).getKeysForRange(doubleFilter.getMin(), doubleFilter.getMax()));
			}).collect(Collectors.toSet())));
		}
	}

	private void addIdSetsForCategoryFilters(Query query, ArrayList<Set<Integer>> filteredIdSets) {
		if(query.categoryFilters != null && !query.categoryFilters.isEmpty()) {
			VariantBucketHolder<VariantMasks> bucketCache = new VariantBucketHolder<VariantMasks>();
			Set<Set<Integer>> idsThatMatchFilters = (Set<Set<Integer>>)query.categoryFilters.keySet().parallelStream().map((String key)->{
				Set<Integer> ids = new TreeSet<Integer>();
				if(pathIsVariantSpec(key)) {
					addIdSetsForVariantSpecCategoryFilters(query.categoryFilters.get(key), key, ids, bucketCache);
				} else {
					String[] categoryFilter = query.categoryFilters.get(key);
					for(String category : categoryFilter) {
						ids.addAll(getCube(key).getKeysForValue(category));
					}
				}
				return ids;
			}).collect(Collectors.toSet());
			filteredIdSets.addAll(idsThatMatchFilters);
		}
	}

	private void addIdSetsForVariantSpecCategoryFilters(String[] zygosities, String key, Set<Integer> ids, VariantBucketHolder<VariantMasks> bucketCache) {
		ArrayList<BigInteger> variantBitmasks = getBitmasksForVariantSpecCategoryFilter(zygosities, key, bucketCache);
		if( ! variantBitmasks.isEmpty()) {
			BigInteger bitmask = variantBitmasks.get(0);
			if(variantBitmasks.size()>1) {
				for(int x = 1;x<variantBitmasks.size();x++) {
					bitmask = bitmask.or(variantBitmasks.get(x));
				}
			}
			String bitmaskString = bitmask.toString(2);
			log.debug("or'd masks : " + bitmaskString);
			// TODO : This is much less efficient than using bitmask.testBit(x)
			for(int x = 2;x < bitmaskString.length()-2;x++) {
				if('1'==bitmaskString.charAt(x)) {
					String patientId = variantStore.getPatientIds()[x-2];
					try{
						ids.add(Integer.parseInt(patientId));
					}catch(NullPointerException | NoSuchElementException e) {
						log.error(ID_CUBE_NAME + " has no value for patientId : " + patientId);
					}
				}
			}
		}
	}

	private ArrayList<BigInteger> getBitmasksForVariantSpecCategoryFilter(String[] zygosities, String variantName, VariantBucketHolder<VariantMasks> bucketCache) {
		ArrayList<BigInteger> variantBitmasks = new ArrayList<>();
		variantName = variantName.replaceAll(",\\d/\\d$", "");
		log.debug("looking up mask for : " + variantName);
		VariantMasks masks;
		try {
			masks = variantStore.getMasks(variantName, bucketCache);
			Arrays.stream(zygosities).forEach((zygosity) -> {
				if(masks!=null) {
					if(zygosity.equals(HOMOZYGOUS_REFERENCE)) {
						BigInteger homozygousReferenceBitmask = calculateIndiscriminateBitmask(masks);
						for(int x = 2;x<homozygousReferenceBitmask.bitLength()-2;x++) {
							homozygousReferenceBitmask = homozygousReferenceBitmask.flipBit(x);
						}
						variantBitmasks.add(homozygousReferenceBitmask);
					} else if(masks.heterozygousMask != null && zygosity.equals(HETEROZYGOUS_VARIANT)) {
						variantBitmasks.add(masks.heterozygousMask);							
					}else if(masks.homozygousMask != null && zygosity.equals(HOMOZYGOUS_VARIANT)) {
						variantBitmasks.add(masks.homozygousMask);
					}else if(zygosity.equals("")) {
						variantBitmasks.add(calculateIndiscriminateBitmask(masks));
					}
				} else {
					variantBitmasks.add(variantStore.emptyBitmask());
				}

			});
		} catch (IOException e) {
			e.printStackTrace();
		}
		return variantBitmasks;
	}

	/**
	 * Calculate a bitmask which is a bitwise OR of any populated masks in the VariantMasks passed in
	 * @param masks
	 * @return
	 */
	private BigInteger calculateIndiscriminateBitmask(VariantMasks masks) {
		BigInteger indiscriminateVariantBitmask = null;
		if(masks.heterozygousMask == null && masks.homozygousMask != null) {
			indiscriminateVariantBitmask = masks.homozygousMask;
		}else if(masks.homozygousMask == null && masks.heterozygousMask != null) {
			indiscriminateVariantBitmask = masks.heterozygousMask;
		}else if(masks.homozygousMask != null && masks.heterozygousMask != null) {
			indiscriminateVariantBitmask = masks.heterozygousMask.or(masks.homozygousMask);
		}else {
			indiscriminateVariantBitmask = variantStore.emptyBitmask();			
		}
		return indiscriminateVariantBitmask;
	}

	protected void addIdSetsForVariantInfoFilters(Query query, ArrayList<Set<Integer>> filteredIdSets) {
//		log.debug("filterdIDSets START size: " + filteredIdSets.size());
		/* VARIANT INFO FILTER HANDLING IS MESSY */
		if(query.variantInfoFilters != null && !query.variantInfoFilters.isEmpty()) {
			for(VariantInfoFilter filter : query.variantInfoFilters){
				ArrayList<Set<String>> variantSets = new ArrayList<>();
				addVariantsMatchingFilters(filter, variantSets);
//				log.info("Found " + variantSets.size() + " groups of sets for patient identification");
				log.info("found " + variantSets.stream().collect(Collectors.summingInt(set->set.size())) + " variants for identification");
				if(!variantSets.isEmpty()) {
					// INTERSECT all the variant sets.
					Set<String> intersectionOfInfoFilters = variantSets.get(0);
					for(Set<String> variantSet : variantSets) {
						intersectionOfInfoFilters = Sets.intersection(intersectionOfInfoFilters, variantSet);
					}
					// Apparently set.size() is really expensive with large sets... I just saw it take 17 seconds for a set with 16.7M entries
					if(log.isDebugEnabled()) {
						IntSummaryStatistics stats = variantSets.stream().collect(Collectors.summarizingInt(set->set.size()));
						log.debug("Number of matching variants for all sets : " + stats.getSum());
						log.debug("Number of matching variants for intersection of sets : " + intersectionOfInfoFilters.size());						
					}
					// add filteredIdSet for patients who have matching variants, heterozygous or homozygous for now.
					addPatientIdsForIntersectionOfVariantSets(filteredIdSets, intersectionOfInfoFilters);
					log.debug("filterdIDSets AFTER size: " + filteredIdSets.size());
				}
			}
		}
		/* END OF VARIANT INFO FILTER HANDLING */
	}

	Weigher<String, int[]> weigher = new Weigher<String, int[]>(){
		@Override
		public int weigh(String key, int[] value) {
			return value.length;
		}
	};

	private void populateVariantIndex() throws InterruptedException {
		int[] numVariants = {0};
		HashMap<String, String[]> contigMap = new HashMap<>();
		
		ExecutorService ex = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());
		variantStore.variantMaskStorage.entrySet().forEach(entry->{
			ex.submit(()->{
				int numVariantsInContig = 0;
				FileBackedByteIndexedStorage<Integer, ConcurrentHashMap<String, VariantMasks>> storage = entry.getValue();
				HashMap<Integer, String[]> bucketMap = new HashMap<>();
				log.info("Creating bucketMap for contig " + entry.getKey());
				for(Integer bucket: storage.keys()){
					try {
						ConcurrentHashMap<String, VariantMasks> bucketStorage = storage.get(bucket);
						numVariantsInContig += bucketStorage.size();
						bucketMap.put(bucket, bucketStorage.keySet().toArray(new String[0]));
					} catch (IOException e) {
						log.error(e);
					}
				};
				log.info("Completed bucketMap for contig " + entry.getKey());
				String[] variantsInContig = new String[numVariantsInContig];
				int current = 0;
				for(String[] bucketList  : bucketMap.values()) {
					System.arraycopy(bucketList, 0, variantsInContig, current, bucketList.length);
					current = current + bucketList.length;
				}
				bucketMap.clear();
				synchronized(numVariants) {
					log.info("Found " + variantsInContig.length + " variants in contig " + entry.getKey() + ".");
					contigMap.put(entry.getKey(), variantsInContig);
					numVariants[0] += numVariantsInContig;
				}
			});
		});
		ex.shutdown();
		while(!ex.awaitTermination(10, TimeUnit.SECONDS)) {
			Thread.sleep(20000);
			log.info("Awaiting completion of variant index");
		}
		
		log.info("Found " + numVariants.length + " total variants.");

		variantIndex = new String[numVariants[0]];

		int current = 0;
		for(String[] contigList  : contigMap.values()) {
			System.arraycopy(contigList, 0, variantIndex, current, contigList.length);
			current = current + contigList.length;
		}
		contigMap.clear();

		Arrays.sort(variantIndex);
		log.info("Index created with " + variantIndex.length + " total variants.");

	}

	protected static String[] variantIndex = null;

	LoadingCache<String, int[]> infoCache = CacheBuilder.newBuilder()
			.weigher(weigher).maximumWeight(500000000).build(new CacheLoader<String, int[]>() {
				@Override
				public int[] load(String infoColumn_valueKey) throws Exception {
					String[] column_and_value = infoColumn_valueKey.split(COLUMN_AND_KEY_DELIMITER);
					String[] variantArray = infoStores.get(column_and_value[0]).allValues.get(column_and_value[1]);
					int[] variantIndexArray = new int[variantArray.length];
					int x = 0;
					for(String variantSpec : variantArray) {
						//we can exclude variants that may be present in the vcf but have no 0/1 or 1/1 samples
						//these variants will still be listed in INFO column lookups (not sample specific),
						//so we need to manually avoid injecting negative values into this array.
						int variantIndexArrayIndex = Arrays.binarySearch(variantIndex, variantSpec);
						if(variantIndexArrayIndex >= 0) {
							variantIndexArray[x++] = variantIndexArrayIndex;
						}
					}
					
					int[] compactedVariantIndexArray = new int[x];
					System.arraycopy(variantIndexArray, 0, compactedVariantIndexArray, 0, x);
					return compactedVariantIndexArray;
				}
			});

	protected void addVariantsMatchingFilters(VariantInfoFilter filter, ArrayList<Set<String>> variantSets) {
		// Add variant sets for each filter
		if(filter.categoryVariantInfoFilters != null && !filter.categoryVariantInfoFilters.isEmpty()) {
			filter.categoryVariantInfoFilters.entrySet().parallelStream().forEach((Entry<String,String[]> entry) ->{
				addVariantsMatchingCategoryFilter(variantSets, entry);
			});
		}
		if(filter.numericVariantInfoFilters != null && !filter.numericVariantInfoFilters.isEmpty()) {
			filter.numericVariantInfoFilters.forEach((String column, FloatFilter doubleFilter)->{
				FileBackedByteIndexedInfoStore infoStore = getInfoStore(column);

				doubleFilter.getMax();
				Range<Float> filterRange = Range.closed(doubleFilter.getMin(), doubleFilter.getMax());
				List<String> valuesInRange = infoStore.continuousValueIndex.getValuesInRange(filterRange);
				Set<String> variants = new LinkedHashSet<String>();
				for(String value : valuesInRange) {
					try {
						variants = Sets.union(variants, arrayToSet(infoCache.get(columnAndKey(column, value))));
					} catch (ExecutionException e) {
						log.error(e);
					}
				}
				variantSets.add(variants);
			});
		}
	}

	private Set<String> arrayToSet(int[] variantSpecs) {
		ConcurrentHashMap<String, String> setMap = new ConcurrentHashMap<String, String>(variantSpecs.length);
		Arrays.stream(variantSpecs).parallel().forEach((index)->{
			String variantSpec = variantIndex[index];
			setMap.put(variantSpec, variantSpec);
		});
		return setMap.keySet();
	}

	private void addVariantsMatchingCategoryFilter(ArrayList<Set<String>> variantSets, Entry<String, String[]> entry) {
		String column = entry.getKey();
		String[] values = entry.getValue();
		Arrays.sort(values);
		FileBackedByteIndexedInfoStore infoStore = getInfoStore(column);

		List<String> infoKeys = filterInfoCategoryKeys(values, infoStore);
		/*
		 * We want to union all the variants for each selected key, so we need an intermediate set
		 */
		Set[] categoryVariantSets = new Set[] {new HashSet<>()};

		if(infoKeys.size()>1) {
			/*
			 *   Because constructing these TreeSets is taking most of the processing time, parallelizing 
			 *   that part of the processing and synchronizing only the adds to the variantSets list.
			 */
			infoKeys.parallelStream().forEach((key)->{
				try {
					Set<String> variantsForColumnAndValue = arrayToSet(infoCache.get(columnAndKey(column, key)));
					synchronized(categoryVariantSets) {
						categoryVariantSets[0] = Sets.union(categoryVariantSets[0], variantsForColumnAndValue);
					}
				} catch (ExecutionException e) {
					log.error(e);
				}
			});
		} else {
			try {
				categoryVariantSets[0] = arrayToSet(infoCache.get(columnAndKey(column, infoKeys.get(0))));
			} catch (ExecutionException e) {
				log.error(e);
			}
		}
		variantSets.add(categoryVariantSets[0]);
	}

	private List<String> filterInfoCategoryKeys(String[] values, FileBackedByteIndexedInfoStore infoStore) {
		List<String> infoKeys = infoStore.allValues.keys().stream().filter((String key)->{

			// iterate over the values for the specific category and find which ones match the search

			int insertionIndex = Arrays.binarySearch(values, key);
			return insertionIndex > -1 && insertionIndex < values.length;
		}).collect(Collectors.toList());
		log.info("found " + infoKeys.size() + " keys");
		return infoKeys;
	}

	private static final String COLUMN_AND_KEY_DELIMITER = "_____";
	private String columnAndKey(String column, String key) {
		return column + COLUMN_AND_KEY_DELIMITER + key;
	}

	private void addPatientIdsForIntersectionOfVariantSets(ArrayList<Set<Integer>> filteredIdSets,
			Set<String> intersectionOfInfoFilters) {
		if(!intersectionOfInfoFilters.isEmpty()) {
			Set<Integer> patientsInScope;
			Set<Integer> patientIds = Arrays.asList(
					variantStore.getPatientIds()).stream().map((String id)->{
						return Integer.parseInt(id);}).collect(Collectors.toSet());
			if(!filteredIdSets.isEmpty()) {
				patientsInScope = Sets.intersection(patientIds, filteredIdSets.get(0));
			} else {
				patientsInScope = patientIds;
			}

			
			BigInteger[] matchingPatients = new BigInteger[] {variantStore.emptyBitmask()};

			ArrayList<List<String>> variantsInScope = new ArrayList<List<String>>(intersectionOfInfoFilters.parallelStream()
					.collect(Collectors.groupingByConcurrent((variantSpec)->{
						return new VariantSpec(variantSpec).metadata.offset/1000;
					})).values());

			List<List<List<String>>> variantPartitions = Lists.partition(variantsInScope, Runtime.getRuntime().availableProcessors());

			int patientsInScopeSize = patientsInScope.size();
			BigInteger patientsInScopeMask = createMaskForPatientSet(patientsInScope);
			for(int x = 0;
					x<variantPartitions.size() 
					&& matchingPatients[0].bitCount() < patientsInScopeSize+4;
					x++) {
				List<List<String>> variantBuckets = variantPartitions.get(x);
				variantBuckets.parallelStream().forEach((variantBucket)->{
//					VariantBucketHolder<VariantMasks> bucketCache = new VariantBucketHolder<VariantMasks>();
					variantBucket.parallelStream().forEach((variantSpec)->{
						VariantMasks masks;
						BigInteger heteroMask = variantStore.emptyBitmask();
						BigInteger homoMask = variantStore.emptyBitmask();
						try {
							masks = variantStore.getMasks(variantSpec, new VariantBucketHolder<VariantMasks>());
							if(masks != null) {
								// Iffing here to avoid all this string parsing and counting when logging not set to DEBUG
//								if(Level.DEBUG.equals(log.getEffectiveLevel())) {
//									log.debug("checking variant " + variantSpec + " for patients: " + ( masks.heterozygousMask == null ? "null" :(masks.heterozygousMask.bitCount() - 4)) 
//											+ "/" + (masks.homozygousMask == null ? "null" : (masks.homozygousMask.bitCount() - 4)) + "    "
//											+ ( masks.heterozygousNoCallMask == null ? "null" :(masks.heterozygousNoCallMask.bitCount() - 4)) 
//											+ "/" + (masks.homozygousNoCallMask == null ? "null" : (masks.homozygousNoCallMask.bitCount() - 4)));
//								}

								heteroMask = masks.heterozygousMask == null ? variantStore.emptyBitmask() : masks.heterozygousMask;
								homoMask = masks.homozygousMask == null ? variantStore.emptyBitmask() : masks.homozygousMask;
								BigInteger orMasks = heteroMask.or(homoMask);
								BigInteger andMasks = orMasks.and(patientsInScopeMask);
								synchronized(matchingPatients) {
									matchingPatients[0] = matchingPatients[0].or(andMasks);
									if(andMasks.bitCount() > 4)
										log.debug("bitcount for matching patients " + variantSpec + ": " + (andMasks.bitCount() - 4));
								}
							} else {
								log.debug("No masks found for variant spec " + variantSpec);
							}
						} catch (IOException e) {
							log.error(e);
						}
					});
				});
			}
			Set<Integer> ids = new TreeSet<Integer>();
			String bitmaskString = matchingPatients[0].toString(2);
			log.debug("or'd masks : " + bitmaskString);
			for(int x = 2;x < bitmaskString.length()-2;x++) {
				if('1'==bitmaskString.charAt(x)) {
					String patientId = variantStore.getPatientIds()[x-2].trim();
					ids.add(Integer.parseInt(patientId));
				}
			}
			filteredIdSets.add(ids);

		}else {
			log.error("No matches found for info filters.");
			filteredIdSets.add(new TreeSet<>());
		}
	}

	protected Collection<String> getVariantList(Query query) throws IOException{
		return processVariantList(query);
	}

	private Collection<String> processVariantList(Query query) throws IOException {
		if(query.variantInfoFilters != null && 
				(!query.variantInfoFilters.isEmpty() && 
						query.variantInfoFilters.stream().anyMatch((entry)->{
							return ((!entry.categoryVariantInfoFilters.isEmpty()) 
									|| (!entry.numericVariantInfoFilters.isEmpty()));
						}))) {
			Set<String> unionOfInfoFilters = new HashSet<>();

			if(query.variantInfoFilters.size()>1) {
				for(VariantInfoFilter filter : query.variantInfoFilters){
					unionOfInfoFilters = addVariantsForInfoFilter(unionOfInfoFilters, filter);
					log.info("filter " + filter + "  sets: " + Arrays.deepToString(unionOfInfoFilters.toArray()));
				}
			} else {
				unionOfInfoFilters = addVariantsForInfoFilter(unionOfInfoFilters, query.variantInfoFilters.get(0));
			}

			Set<Integer> patientSubset = Sets.intersection(getPatientSubsetForQuery(query), 
					new HashSet<Integer>(
							Arrays.asList(variantStore.getPatientIds()).stream()
							.map((id)->{return Integer.parseInt(id.trim());})
							.collect(Collectors.toList())));
			log.debug("Patient subset " + Arrays.deepToString(patientSubset.toArray()));

			// If we have all patients then no variants would be filtered, so no need to do further processing
			if(patientSubset.size()==variantStore.getPatientIds().length) {
				log.info("query selects all patient IDs, returning....");
				return new ArrayList<String>(unionOfInfoFilters);
			}

			BigInteger patientMasks = createMaskForPatientSet(patientSubset);

//			Collection<String> variantsInScope = bucketIndex.filterVariantSetForPatientSet(unionOfInfoFilters, new ArrayList<>(patientSubset));
			Collection<String> variantsInScope = unionOfInfoFilters;
			log.info("Variants in scope (no bucket filtering): " + variantsInScope.size());
			
			
			//I think that this next section is filtering the variant list AGAIN, which we probably don't need
			
			if(variantsInScope.size()<100000) {
				ConcurrentSkipListSet<String> variantsWithPatients = new ConcurrentSkipListSet<String>();
				variantsInScope.parallelStream().forEach((String variantKey)->{
					VariantMasks masks;
					try {
						masks = variantStore.getMasks(variantKey, new VariantBucketHolder<VariantMasks>());
						if ( masks.heterozygousMask != null && masks.heterozygousMask.and(patientMasks).bitCount()>4) {
							variantsWithPatients.add(variantKey);
						} else if ( masks.homozygousMask != null && masks.homozygousMask.and(patientMasks).bitCount()>4) {
							variantsWithPatients.add(variantKey);
						} else if ( masks.heterozygousNoCallMask != null && masks.heterozygousNoCallMask.and(patientMasks).bitCount()>4) {
							//so heterozygous no calls we want, homozygous no calls we don't
							variantsWithPatients.add(variantKey);
//						} else {
//							log.debug("no patients found for variant " + variantKey);
//							log.debug("Variant hetero Mask " + masks.heterozygousMask);
//							log.debug("variant homo Mask   " + masks.homozygousMask);
							
						}
					} catch (IOException e) {
						log.error(e);
					}
				});
				return variantsWithPatients;
			}else {
				return unionOfInfoFilters;
			}
		}
		return new ArrayList<>();
	}

	private Set<String> addVariantsForInfoFilter(Set<String> unionOfInfoFilters, VariantInfoFilter filter) {
		ArrayList<Set<String>> variantSets = new ArrayList<>();
		addVariantsMatchingFilters(filter, variantSets);

		if(!variantSets.isEmpty()) {
			if(variantSets.size()>1) {
				Set<String> intersectionOfInfoFilters = variantSets.get(0);
				for(Set<String> variantSet : variantSets) {
					//						log.info("Variant Set : " + Arrays.deepToString(variantSet.toArray()));
					intersectionOfInfoFilters = Sets.intersection(intersectionOfInfoFilters, variantSet);
				}
				unionOfInfoFilters = Sets.union(unionOfInfoFilters, intersectionOfInfoFilters);
			} else {
				unionOfInfoFilters = Sets.union(unionOfInfoFilters, variantSets.get(0));
			}
		} else {
			log.warn("No info filters included in query.");
		}
		return unionOfInfoFilters;
	}

	protected BigInteger createMaskForPatientSet(Set<Integer> patientSubset) {
		StringBuilder builder = new StringBuilder("11"); //variant bitmasks are bookended with '11'
		for(String patientId : variantStore.getPatientIds()) {
			Integer idInt = Integer.parseInt(patientId);
			if(patientSubset.contains(idInt)){
				builder.append("1");
			} else {
				builder.append("0");
			}
		}
		builder.append("11"); // masks are bookended with '11' set this so we don't count those

		log.debug("PATIENT MASK: " + builder.toString());

		BigInteger patientMasks = new BigInteger(builder.toString(), 2);
		return patientMasks;
	}

	public FileBackedByteIndexedInfoStore getInfoStore(String column) {
		return infoStores.get(column);
	}
	//
	//	private boolean pathIsGeneName(String key) {
	//		return new GeneLibrary().geneNameSearch(key).size()==1;
	//	}

	public boolean pathIsVariantSpec(String key) {
		return key.matches("rs[0-9]+.*") || key.matches(".*,[0-9\\\\.]+,[CATGcatg]*,[CATGcatg]*");
	}

	/**
	 * If there are concepts in the list of paths which are already in the cache, push those to the
	 * front of the list so that we don't evict and then reload them for concepts which are not yet
	 * in the cache.
	 * 
	 * @param paths
	 * @param columnCount
	 * @return
	 */
	protected ArrayList<Integer> useResidentCubesFirst(List<String> paths, int columnCount) {
		int x;
		TreeSet<String> pathSet = new TreeSet<String>(paths);
		Set<String> residentKeys = Sets.intersection(pathSet, store.asMap().keySet());

		ArrayList<Integer> columnIndex = new ArrayList<Integer>();

		residentKeys.forEach(key ->{
			columnIndex.add(paths.indexOf(key) + 1);
		});

		Sets.difference(pathSet, residentKeys).forEach(key->{
			columnIndex.add(paths.indexOf(key) + 1);
		});

		for(x = 1;x < columnCount;x++) {
			columnIndex.add(x);
		}
		return columnIndex;
	}

	/**
	 * Load the variantStore object from disk and build the PhenoCube cache.
	 * 
	 * @return
	 * @throws ClassNotFoundException
	 * @throws FileNotFoundException
	 * @throws IOException
	 */
	protected LoadingCache<String, PhenoCube<?>> initializeCache() throws ClassNotFoundException, FileNotFoundException, IOException {
		if(new File("/opt/local/hpds/all/variantStore.javabin").exists()) {

			ObjectInputStream ois = new ObjectInputStream(new GZIPInputStream(new FileInputStream("/opt/local/hpds/all/variantStore.javabin")));
			variantStore = (VariantStore) ois.readObject();
			ois.close();
			variantStore.open();			
		} else {
			//we still need an object to reference when checking the variant store, even if it's empty.
			variantStore = new VariantStore();
			variantStore.setPatientIds(new String[0]);
		}
		return CacheBuilder.newBuilder()
				.maximumSize(CACHE_SIZE)
				.build(
						new CacheLoader<String, PhenoCube<?>>() {
							public PhenoCube<?> load(String key) throws Exception {
								try(RandomAccessFile allObservationsStore = new RandomAccessFile("/opt/local/hpds/allObservationsStore.javabin", "r");){
									ColumnMeta columnMeta = metaStore.get(key);
									if(columnMeta != null) {
										allObservationsStore.seek(columnMeta.getAllObservationsOffset());
										int length = (int) (columnMeta.getAllObservationsLength() - columnMeta.getAllObservationsOffset());
										byte[] buffer = new byte[length];
										allObservationsStore.read(buffer);
										allObservationsStore.close();
										ObjectInputStream inStream = new ObjectInputStream(new ByteArrayInputStream(Crypto.decryptData(buffer)));
										PhenoCube<?> ret = (PhenoCube<?>)inStream.readObject();
										inStream.close();
										return ret;																		
									}else {
										System.out.println("ColumnMeta not found for : [" + key + "]");
										return null;
									}
								}
							}
						});
	}

	/**
	 * Prime the cache if we have a key already by loading PhenoCubes into the cache up to maximum CACHE_SIZE
	 * 
	 */
	public synchronized void loadAllDataFiles() {
		if(!dataFilesLoaded) {
			if(Crypto.hasKey(Crypto.DEFAULT_KEY_NAME)) {
				List<String> cubes = new ArrayList<String>(metaStore.keySet());
				int conceptsToCache = Math.min(metaStore.size(), CACHE_SIZE);
				for(int x = 0;x<conceptsToCache;x++){
					try {
						if(metaStore.get(cubes.get(x)).getObservationCount() == 0){
							log.info("Rejecting : " + cubes.get(x) + " because it has no entries.");
						}else {
							store.get(cubes.get(x));
							log.debug("loaded: " + cubes.get(x));
							// +1 offset when logging to print _after_ each 10%
							if((x + 1) % (conceptsToCache * .1)== 0) {
								log.info("cached: " + (x + 1) + " out of " + conceptsToCache);	
							}
						}
					} catch (ExecutionException e) {
						log.error(e);
					}

				}

			}
			infoStores = new HashMap<>();
			File genomicDataDirectory = new File("/opt/local/hpds/all/");
			if(genomicDataDirectory.exists() && genomicDataDirectory.isDirectory()) {
				Arrays.stream(genomicDataDirectory.list((file, filename)->{return filename.endsWith("infoStore.javabin");}))
				.forEach((String filename)->{
					try (
							FileInputStream fis = new FileInputStream("/opt/local/hpds/all/" + filename);
							GZIPInputStream gis = new GZIPInputStream(fis);
							ObjectInputStream ois = new ObjectInputStream(gis)
							){
						log.info("loading " + filename);
						FileBackedByteIndexedInfoStore infoStore = (FileBackedByteIndexedInfoStore) ois.readObject();
						infoStores.put(filename.replace("_infoStore.javabin", ""), infoStore);	
						ois.close();
					} catch (FileNotFoundException e) {
						e.printStackTrace();
					} catch (IOException e) {
						e.printStackTrace();
					} catch (ClassNotFoundException e) {
						e.printStackTrace();
					}
				});
			}
			try {
				loadGenomicCacheFiles();
			} catch (Throwable e) {
				log.error("Failed to load genomic data: " + e.getLocalizedMessage(), e);
			}
			dataFilesLoaded = true;
		}
	}

	protected PhenoCube getCube(String path) {
		try { 
			return store.get(path);
		} catch (ExecutionException e) {
			throw new RuntimeException(e);
		}
	}

	public TreeMap<String, ColumnMeta> getDictionary() {
		return metaStore;
	}

	/**
	 * Execute whatever processing is required for the particular implementation of AbstractProcessor
	 * 
	 * @param query
	 * @param asyncResult
	 * @throws NotEnoughMemoryException
	 */
	public abstract void runQuery(Query query, AsyncResult asyncResult) throws NotEnoughMemoryException;

}
