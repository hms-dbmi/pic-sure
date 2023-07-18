package edu.harvard.hms.dbmi.avillach.hpds.processing;

import java.io.*;
import java.math.BigInteger;
import java.util.*;
import java.util.Map.Entry;
import java.util.concurrent.*;
import java.util.stream.Collectors;
import java.util.zip.GZIPInputStream;

import com.google.common.util.concurrent.UncheckedExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.cache.*;
import com.google.common.cache.CacheLoader.InvalidCacheLoadException;
import com.google.common.collect.Range;
import com.google.common.collect.Sets;

import edu.harvard.hms.dbmi.avillach.hpds.crypto.Crypto;
import edu.harvard.hms.dbmi.avillach.hpds.data.genotype.*;
import edu.harvard.hms.dbmi.avillach.hpds.data.genotype.caching.VariantBucketHolder;
import edu.harvard.hms.dbmi.avillach.hpds.data.phenotype.ColumnMeta;
import edu.harvard.hms.dbmi.avillach.hpds.data.phenotype.PhenoCube;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.Filter.FloatFilter;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.Query;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.Query.VariantInfoFilter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;


@Component
public class AbstractProcessor {

	private static Logger log = LoggerFactory.getLogger(AbstractProcessor.class);

	private final String HOMOZYGOUS_VARIANT = "1/1";
	private final String HETEROZYGOUS_VARIANT = "0/1";
	private final String HOMOZYGOUS_REFERENCE = "0/0";

	private final String ID_CUBE_NAME;
	private final int ID_BATCH_SIZE;
	private final int CACHE_SIZE;

	private final String hpdsDataDirectory;
	private final String genomicDataDirectory;



	private List<String> infoStoreColumns;

	private Map<String, FileBackedByteIndexedInfoStore> infoStores;

	private LoadingCache<String, PhenoCube<?>> store;

	private final VariantService variantService;

	private final PhenotypeMetaStore phenotypeMetaStore;

	private final VariantIndexCache variantIndexCache;

	private final PatientVariantJoinHandler patientVariantJoinHandler;

	private final LoadingCache<String, List<String>> infoStoreValuesCache = CacheBuilder.newBuilder().build(new CacheLoader<>() {
		@Override
		public List<String> load(String conceptPath) {
			FileBackedByteIndexedInfoStore store = getInfoStore(conceptPath);
			if (store == null) {
				throw new IllegalArgumentException("Concept path: " + conceptPath + " not found");
			} else if (store.isContinuous) {
				throw new IllegalArgumentException("Concept path: " + conceptPath + " is not categorical");
			}
			return store.getAllValues().keys()
					.stream()
					.sorted(String::compareToIgnoreCase)
					.collect(Collectors.toList());
		}
	});

	@Autowired
	public AbstractProcessor(PhenotypeMetaStore phenotypeMetaStore, VariantService variantService, PatientVariantJoinHandler patientVariantJoinHandler) throws ClassNotFoundException, IOException, InterruptedException {
		this.phenotypeMetaStore = phenotypeMetaStore;
		this.variantService = variantService;
		this.patientVariantJoinHandler = patientVariantJoinHandler;

		CACHE_SIZE = Integer.parseInt(System.getProperty("CACHE_SIZE", "100"));
		ID_BATCH_SIZE = Integer.parseInt(System.getProperty("ID_BATCH_SIZE", "0"));
		ID_CUBE_NAME = System.getProperty("ID_CUBE_NAME", "NONE");

		hpdsDataDirectory = System.getProperty("HPDS_DATA_DIRECTORY", "/opt/local/hpds/");
		genomicDataDirectory = System.getProperty("HPDS_GENOMIC_DATA_DIRECTORY", "/opt/local/hpds/all/");

		store = initializeCache();

		if(Crypto.hasKey(Crypto.DEFAULT_KEY_NAME)) {
			List<String> cubes = new ArrayList<String>(phenotypeMetaStore.getColumnNames());
			int conceptsToCache = Math.min(cubes.size(), CACHE_SIZE);
			for(int x = 0;x<conceptsToCache;x++){
				try {
					if(phenotypeMetaStore.getColumnMeta(cubes.get(x)).getObservationCount() == 0){
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
					log.error("an error occurred", e);
				}

			}

		}
		infoStores = new HashMap<>();
		File genomicDataDirectory = new File(this.genomicDataDirectory);
		if(genomicDataDirectory.exists() && genomicDataDirectory.isDirectory()) {
			Arrays.stream(genomicDataDirectory.list((file, filename)->{return filename.endsWith("infoStore.javabin");}))
					.forEach((String filename)->{
						try (
								FileInputStream fis = new FileInputStream(this.genomicDataDirectory + filename);
								GZIPInputStream gis = new GZIPInputStream(fis);
								ObjectInputStream ois = new ObjectInputStream(gis)
						){
							log.info("loading " + filename);
							FileBackedByteIndexedInfoStore infoStore = (FileBackedByteIndexedInfoStore) ois.readObject();
							infoStores.put(filename.replace("_infoStore.javabin", ""), infoStore);
							ois.close();
						} catch (IOException e) {
							throw new UncheckedIOException(e);
						} catch (ClassNotFoundException e) {
							throw new RuntimeException(e);
						}
					});
		}
		infoStoreColumns = new ArrayList<>(infoStores.keySet());

		variantIndexCache = new VariantIndexCache(variantService.getVariantIndex(), infoStores);
		warmCaches();
	}

	public AbstractProcessor(PhenotypeMetaStore phenotypeMetaStore, LoadingCache<String, PhenoCube<?>> store,
							 Map<String, FileBackedByteIndexedInfoStore> infoStores, List<String> infoStoreColumns,
							 VariantService variantService, VariantIndexCache variantIndexCache, PatientVariantJoinHandler patientVariantJoinHandler) {
		this.phenotypeMetaStore = phenotypeMetaStore;
		this.store = store;
		this.infoStores = infoStores;
		this.infoStoreColumns = infoStoreColumns;
		this.variantService = variantService;
		this.variantIndexCache = variantIndexCache;
		this.patientVariantJoinHandler = patientVariantJoinHandler;

		CACHE_SIZE = Integer.parseInt(System.getProperty("CACHE_SIZE", "100"));
		ID_BATCH_SIZE = Integer.parseInt(System.getProperty("ID_BATCH_SIZE", "0"));
		ID_CUBE_NAME = System.getProperty("ID_CUBE_NAME", "NONE");

		hpdsDataDirectory = System.getProperty("HPDS_DATA_DIRECTORY", "/opt/local/hpds/");
		genomicDataDirectory = System.getProperty("HPDS_GENOMIC_DATA_DIRECTORY", "/opt/local/hpds/all/");
	}

	public List<String> getInfoStoreColumns() {
		return infoStoreColumns;
	}

	private void warmCaches() {
		//infoCache.refresh("Variant_frequency_as_text_____Rare");
		//infoCache.refresh("Variant_frequency_as_text_____Common");
		//infoCache.refresh("Variant_frequency_as_text_____Novel");
	}


	/**
	 * Merges a list of sets of patient ids by intersection. If we implemented OR semantics
	 * this would be where the change happens.
	 *
	 * @param filteredIdSets
	 * @return
	 */
	protected Set<Integer> applyBooleanLogic(List<Set<Integer>> filteredIdSets) {
		Set<Integer>[] ids = new Set[] {filteredIdSets.get(0)};
		filteredIdSets.forEach((keySet)->{
			ids[0] = Sets.intersection(ids[0], keySet);
		});
		return ids[0];
	}

	/**
	 * For each filter in the query, return a set of patient ids that match. The order of these sets in the
	 * returned list of sets does not matter and cannot currently be tied back to the filter that generated
	 * it.
	 *
	 * @param query
	 * @return
	 */
	protected List<Set<Integer>> idSetsForEachFilter(Query query) {
		ArrayList<Set<Integer>> filteredIdSets = new ArrayList<Set<Integer>>();

		try {
			addIdSetsForAnyRecordOf(query, filteredIdSets);
			addIdSetsForRequiredFields(query, filteredIdSets);
			addIdSetsForNumericFilters(query, filteredIdSets);
			addIdSetsForCategoryFilters(query, filteredIdSets);
		} catch (InvalidCacheLoadException e) {
			log.warn("Invalid query supplied: " + e.getLocalizedMessage());
			filteredIdSets.add(new HashSet<Integer>()); // if an invalid path is supplied, no patients should match.
		}

		//AND logic to make sure all patients match each filter
		if(filteredIdSets.size()>1) {
			filteredIdSets = new ArrayList<Set<Integer>>(List.of(applyBooleanLogic(filteredIdSets)));
		}

		return addIdSetsForVariantInfoFilters(query, filteredIdSets);
	}

	/**
	 * Process each filter in the query and return a list of patient ids that should be included in the
	 * result.
	 *
	 * @param query
	 * @return
	 */
	public TreeSet<Integer> getPatientSubsetForQuery(Query query) {
		List<Set<Integer>> filteredIdSets;

		filteredIdSets = idSetsForEachFilter(query);

		TreeSet<Integer> idList;
		if(filteredIdSets.isEmpty()) {
			if(variantService.getPatientIds().length > 0 ) {
				idList = new TreeSet(
						Sets.union(phenotypeMetaStore.getPatientIds(),
								new TreeSet(Arrays.asList(
										variantService.getPatientIds()).stream()
										.collect(Collectors.mapping(
												(String id)->{return Integer.parseInt(id.trim());}, Collectors.toList()))) ));
			}else {
				idList = phenotypeMetaStore.getPatientIds();
			}
		}else {
			idList = new TreeSet<>(applyBooleanLogic(filteredIdSets));
		}
		return idList;
	}

	private void addIdSetsForRequiredFields(Query query, ArrayList<Set<Integer>> filteredIdSets) {
		if(!query.getRequiredFields().isEmpty()) {
			VariantBucketHolder<VariantMasks> bucketCache = new VariantBucketHolder<>();
			filteredIdSets.addAll(query.getRequiredFields().parallelStream().map(path->{
				if(VariantUtils.pathIsVariantSpec(path)) {
					TreeSet<Integer> patientsInScope = new TreeSet<>();
					addIdSetsForVariantSpecCategoryFilters(new String[]{"0/1","1/1"}, path, patientsInScope, bucketCache);
					return patientsInScope;
				} else {
					return new TreeSet<Integer>(getCube(path).keyBasedIndex());
				}
			}).collect(Collectors.toSet()));
		}
	}

	private void addIdSetsForAnyRecordOf(Query query, ArrayList<Set<Integer>> filteredIdSets) {
		if(!query.getAnyRecordOf().isEmpty()) {
			Set<Integer> patientsInScope = new ConcurrentSkipListSet<Integer>();
			VariantBucketHolder<VariantMasks> bucketCache = new VariantBucketHolder<VariantMasks>();
			query.getAnyRecordOf().parallelStream().forEach(path->{
				if(patientsInScope.size()<Math.max(
						phenotypeMetaStore.getPatientIds().size(),
						variantService.getPatientIds().length)) {
					if(VariantUtils.pathIsVariantSpec(path)) {
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
		if(!query.getNumericFilters().isEmpty()) {
			filteredIdSets.addAll((Set<TreeSet<Integer>>)(query.getNumericFilters().entrySet().parallelStream().map(entry->{
				return (TreeSet<Integer>)(getCube(entry.getKey()).getKeysForRange(entry.getValue().getMin(), entry.getValue().getMax()));
			}).collect(Collectors.toSet())));
		}
	}

	private void addIdSetsForCategoryFilters(Query query, ArrayList<Set<Integer>> filteredIdSets) {
		if(!query.getCategoryFilters().isEmpty()) {
			VariantBucketHolder<VariantMasks> bucketCache = new VariantBucketHolder<VariantMasks>();
			Set<Set<Integer>> idsThatMatchFilters = (Set<Set<Integer>>)query.getCategoryFilters().entrySet().parallelStream().map(entry->{
				Set<Integer> ids = new TreeSet<Integer>();
				if(VariantUtils.pathIsVariantSpec(entry.getKey())) {
					addIdSetsForVariantSpecCategoryFilters(entry.getValue(), entry.getKey(), ids, bucketCache);
				} else {
					String[] categoryFilter = entry.getValue();
					for(String category : categoryFilter) {
							ids.addAll(getCube(entry.getKey()).getKeysForValue(category));
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
					String patientId = variantService.getPatientIds()[x-2];
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
		masks = variantService.getMasks(variantName, bucketCache);
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
				variantBitmasks.add(variantService.emptyBitmask());
			}

		});
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
			indiscriminateVariantBitmask = variantService.emptyBitmask();
		}
		return indiscriminateVariantBitmask;
	}

	protected List<Set<Integer>> addIdSetsForVariantInfoFilters(Query query, List<Set<Integer>> filteredIdSets) {
//		log.debug("filterdIDSets START size: " + filteredIdSets.size());
		/* VARIANT INFO FILTER HANDLING IS MESSY */
		if(!query.getVariantInfoFilters().isEmpty()) {
			for(VariantInfoFilter filter : query.getVariantInfoFilters()){
				ArrayList<VariantIndex> variantSets = new ArrayList<>();
				addVariantsMatchingFilters(filter, variantSets);
				log.info("Found " + variantSets.size() + " groups of sets for patient identification");
				//log.info("found " + variantSets.stream().mapToInt(Set::size).sum() + " variants for identification");
				if(!variantSets.isEmpty()) {
					// INTERSECT all the variant sets.
					VariantIndex intersectionOfInfoFilters = variantSets.get(0);
					for(VariantIndex variantSet : variantSets) {
						intersectionOfInfoFilters = intersectionOfInfoFilters.intersection(variantSet);
					}
					// Apparently set.size() is really expensive with large sets... I just saw it take 17 seconds for a set with 16.7M entries
					if(log.isDebugEnabled()) {
						//IntSummaryStatistics stats = variantSets.stream().collect(Collectors.summarizingInt(set->set.size()));
						//log.debug("Number of matching variants for all sets : " + stats.getSum());
						//log.debug("Number of matching variants for intersection of sets : " + intersectionOfInfoFilters.size());
					}
					// add filteredIdSet for patients who have matching variants, heterozygous or homozygous for now.
					return patientVariantJoinHandler.getPatientIdsForIntersectionOfVariantSets(filteredIdSets, intersectionOfInfoFilters);
				}
			}
		}
		return filteredIdSets;
		/* END OF VARIANT INFO FILTER HANDLING */
	}

	protected void addVariantsMatchingFilters(VariantInfoFilter filter, ArrayList<VariantIndex> variantSets) {
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
				VariantIndex variants = new SparseVariantIndex(Set.of());
				for(String value : valuesInRange) {
					variants = variants.union(variantIndexCache.get(column, value));
				}
				variantSets.add(variants);
			});
		}
	}

	private void addVariantsMatchingCategoryFilter(ArrayList<VariantIndex> variantSets, Entry<String, String[]> entry) {
		String column = entry.getKey();
		String[] values = entry.getValue();
		Arrays.sort(values);
		FileBackedByteIndexedInfoStore infoStore = getInfoStore(column);

		List<String> infoKeys = filterInfoCategoryKeys(values, infoStore);
		/*
		 * We want to union all the variants for each selected key, so we need an intermediate set
		 */
		VariantIndex[] categoryVariantSets = new VariantIndex[] {new SparseVariantIndex(Set.of())};

		if(infoKeys.size()>1) {
			infoKeys.stream().forEach((key)->{
				VariantIndex variantsForColumnAndValue = variantIndexCache.get(column, key);
				categoryVariantSets[0] = categoryVariantSets[0].union(variantsForColumnAndValue);
			});
		} else {
			categoryVariantSets[0] = variantIndexCache.get(column, infoKeys.get(0));
		}
		variantSets.add(categoryVariantSets[0]);
	}

	private List<String> filterInfoCategoryKeys(String[] values, FileBackedByteIndexedInfoStore infoStore) {
		List<String> infoKeys = infoStore.getAllValues().keys().stream().filter((String key)->{
			// iterate over the values for the specific category and find which ones match the search
			int insertionIndex = Arrays.binarySearch(values, key);
			return insertionIndex > -1 && insertionIndex < values.length;
		}).collect(Collectors.toList());
		log.info("found " + infoKeys.size() + " keys");
		return infoKeys;
	}

	protected Collection<String> getVariantList(Query query) throws IOException {
		return processVariantList(query);
	}

	private Collection<String> processVariantList(Query query) throws IOException {
		boolean queryContainsVariantInfoFilters = query.getVariantInfoFilters().stream().anyMatch(variantInfoFilter ->
				!variantInfoFilter.categoryVariantInfoFilters.isEmpty() || !variantInfoFilter.numericVariantInfoFilters.isEmpty()
		);
		if(queryContainsVariantInfoFilters) {
			VariantIndex unionOfInfoFilters = new SparseVariantIndex(Set.of());

			// todo: are these not the same thing?
			if(query.getVariantInfoFilters().size()>1) {
				for(VariantInfoFilter filter : query.getVariantInfoFilters()){
					unionOfInfoFilters = addVariantsForInfoFilter(unionOfInfoFilters, filter);
					//log.info("filter " + filter + "  sets: " + Arrays.deepToString(unionOfInfoFilters.toArray()));
				}
			} else {
				unionOfInfoFilters = addVariantsForInfoFilter(unionOfInfoFilters, query.getVariantInfoFilters().get(0));
			}

			TreeSet<Integer> patientSubsetForQuery = getPatientSubsetForQuery(query);
			HashSet<Integer> allPatients = new HashSet<>(
					Arrays.stream(variantService.getPatientIds())
							.map((id) -> {
								return Integer.parseInt(id.trim());
							})
							.collect(Collectors.toList()));
			Set<Integer> patientSubset = Sets.intersection(patientSubsetForQuery, allPatients);
//			log.debug("Patient subset " + Arrays.deepToString(patientSubset.toArray()));

			// If we have all patients then no variants would be filtered, so no need to do further processing
			if(patientSubset.size()==variantService.getPatientIds().length) {
				log.info("query selects all patient IDs, returning....");
				return unionOfInfoFilters.mapToVariantSpec(variantService.getVariantIndex());
			}

			// todo: continue testing from here. Also, hasn't this been done in PatientVarientJoinHandler?
			BigInteger patientMasks = createMaskForPatientSet(patientSubset);

			Set<String> unionOfInfoFiltersVariantSpecs = unionOfInfoFilters.mapToVariantSpec(variantService.getVariantIndex());
			Collection<String> variantsInScope = variantService.filterVariantSetForPatientSet(unionOfInfoFiltersVariantSpecs, new ArrayList<>(patientSubset));

			//NC - this is the original variant filtering, which checks the patient mask from each variant against the patient mask from the query
			if(variantsInScope.size()<100000) {
				ConcurrentSkipListSet<String> variantsWithPatients = new ConcurrentSkipListSet<String>();
				variantsInScope.parallelStream().forEach((String variantKey)->{
					VariantMasks masks = variantService.getMasks(variantKey, new VariantBucketHolder<VariantMasks>());
					if ( masks.heterozygousMask != null && masks.heterozygousMask.and(patientMasks).bitCount()>4) {
						variantsWithPatients.add(variantKey);
					} else if ( masks.homozygousMask != null && masks.homozygousMask.and(patientMasks).bitCount()>4) {
						variantsWithPatients.add(variantKey);
					} else if ( masks.heterozygousNoCallMask != null && masks.heterozygousNoCallMask.and(patientMasks).bitCount()>4) {
						//so heterozygous no calls we want, homozygous no calls we don't
						variantsWithPatients.add(variantKey);
					}
				});
				return variantsWithPatients;
			}else {
				return unionOfInfoFiltersVariantSpecs;
			}
		}
		return new ArrayList<>();
	}

	private VariantIndex addVariantsForInfoFilter(VariantIndex unionOfInfoFilters, VariantInfoFilter filter) {
		ArrayList<VariantIndex> variantSets = new ArrayList<>();
		addVariantsMatchingFilters(filter, variantSets);

		if(!variantSets.isEmpty()) {
			VariantIndex intersectionOfInfoFilters = variantSets.get(0);
			for(VariantIndex variantSet : variantSets) {
				//						log.info("Variant Set : " + Arrays.deepToString(variantSet.toArray()));
				intersectionOfInfoFilters = intersectionOfInfoFilters.intersection(variantSet);
			}
			unionOfInfoFilters = unionOfInfoFilters.union(intersectionOfInfoFilters);
		} else {
			log.warn("No info filters included in query.");
		}
		return unionOfInfoFilters;
	}

	protected BigInteger createMaskForPatientSet(Set<Integer> patientSubset) {
		return patientVariantJoinHandler.createMaskForPatientSet(patientSubset);
	}

	public FileBackedByteIndexedInfoStore getInfoStore(String column) {
		return infoStores.get(column);
	}

	public List<String> searchInfoConceptValues(String conceptPath, String query) {
		try {
			return infoStoreValuesCache.getUnchecked(conceptPath).stream()
					.filter(variableValue -> variableValue.toUpperCase().contains(query.toUpperCase()))
					.collect(Collectors.toList());
		} catch (UncheckedExecutionException e) {
			if(e.getCause() instanceof RuntimeException) {
				throw (RuntimeException) e.getCause();
			}
			throw e;
		}
	}

	//
	//	private boolean pathIsGeneName(String key) {
	//		return new GeneLibrary().geneNameSearch(key).size()==1;
	//	}

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
	 */
	protected LoadingCache<String, PhenoCube<?>> initializeCache() {
		return CacheBuilder.newBuilder()
				.maximumSize(CACHE_SIZE)
				.build(
						new CacheLoader<String, PhenoCube<?>>() {
							public PhenoCube<?> load(String key) throws Exception {
								try(RandomAccessFile allObservationsStore = new RandomAccessFile(hpdsDataDirectory + "allObservationsStore.javabin", "r");){
									ColumnMeta columnMeta = phenotypeMetaStore.getColumnMeta(key);
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


	protected PhenoCube getCube(String path) {
		try {
			return store.get(path);
		} catch (ExecutionException e) {
			throw new RuntimeException(e);
		}
	}

	public TreeMap<String, ColumnMeta> getDictionary() {
		return phenotypeMetaStore.getMetaStore();
	}

	public String[] getPatientIds() {
		return variantService.getPatientIds();
	}

	public VariantMasks getMasks(String path, VariantBucketHolder<VariantMasks> variantMasksVariantBucketHolder) {
		return variantService.getMasks(path, variantMasksVariantBucketHolder);
	}
}
