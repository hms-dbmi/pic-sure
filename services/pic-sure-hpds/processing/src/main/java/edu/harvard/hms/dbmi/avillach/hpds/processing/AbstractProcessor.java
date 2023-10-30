package edu.harvard.hms.dbmi.avillach.hpds.processing;

import java.io.*;
import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;

import com.google.common.util.concurrent.UncheckedExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.cache.*;
import com.google.common.cache.CacheLoader.InvalidCacheLoadException;
import com.google.common.collect.Sets;

import edu.harvard.hms.dbmi.avillach.hpds.crypto.Crypto;
import edu.harvard.hms.dbmi.avillach.hpds.data.genotype.*;
import edu.harvard.hms.dbmi.avillach.hpds.data.genotype.caching.VariantBucketHolder;
import edu.harvard.hms.dbmi.avillach.hpds.data.phenotype.ColumnMeta;
import edu.harvard.hms.dbmi.avillach.hpds.data.phenotype.PhenoCube;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.Query;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.Query.VariantInfoFilter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;


@Component
public class AbstractProcessor {

	private static Logger log = LoggerFactory.getLogger(AbstractProcessor.class);

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

	private final GenomicProcessor genomicProcessor;

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
	public AbstractProcessor(
			PhenotypeMetaStore phenotypeMetaStore,
			GenomicProcessor genomicProcessor
	) throws ClassNotFoundException, IOException, InterruptedException {
		hpdsDataDirectory = System.getProperty("HPDS_DATA_DIRECTORY", "/opt/local/hpds/");
		genomicDataDirectory = System.getProperty("HPDS_GENOMIC_DATA_DIRECTORY", "/opt/local/hpds/all/");

		this.phenotypeMetaStore = phenotypeMetaStore;
		// todo: get rid of this
		this.variantService = new VariantService(genomicDataDirectory);
		this.genomicProcessor = genomicProcessor;

		CACHE_SIZE = Integer.parseInt(System.getProperty("CACHE_SIZE", "100"));
		ID_BATCH_SIZE = Integer.parseInt(System.getProperty("ID_BATCH_SIZE", "0"));
		ID_CUBE_NAME = System.getProperty("ID_CUBE_NAME", "NONE");

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
							infoStore.updateStorageDirectory(genomicDataDirectory);
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
	}

	public AbstractProcessor(PhenotypeMetaStore phenotypeMetaStore, LoadingCache<String, PhenoCube<?>> store,
							 Map<String, FileBackedByteIndexedInfoStore> infoStores, List<String> infoStoreColumns,
							 VariantService variantService, GenomicProcessor genomicProcessor) {
		this.phenotypeMetaStore = phenotypeMetaStore;
		this.store = store;
		this.infoStores = infoStores;
		this.infoStoreColumns = infoStoreColumns;
		this.variantService = variantService;
		this.genomicProcessor = genomicProcessor;

		CACHE_SIZE = Integer.parseInt(System.getProperty("CACHE_SIZE", "100"));
		ID_BATCH_SIZE = Integer.parseInt(System.getProperty("ID_BATCH_SIZE", "0"));
		ID_CUBE_NAME = System.getProperty("ID_CUBE_NAME", "NONE");

		hpdsDataDirectory = System.getProperty("HPDS_DATA_DIRECTORY", "/opt/local/hpds/");
		genomicDataDirectory = System.getProperty("HPDS_GENOMIC_DATA_DIRECTORY", "/opt/local/hpds/all/");
	}

	public List<String> getInfoStoreColumns() {
		return infoStoreColumns;
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
			// I believe this is not ideal for large numbers of sets. Sets.intersection creates a view of the 2 sets, so
			// doing this repeatedly would create views on views on views of the backing sets. retainAll() returns a new
			// set with only the common elements, and if we sort by set size and start with the smallest I believe that
			// will be more efficient
			ids[0] = Sets.intersection(ids[0], keySet);
		});
		return ids[0];
	}

	/**
	 *
	 * @param query
	 * @return
	 */
	protected Set<Integer> idSetsForEachFilter(Query query) {
		DistributableQuery distributableQuery = getDistributableQuery(query);

		if (distributableQuery.hasFilters()) {
            BigInteger patientMaskForVariantInfoFilters = genomicProcessor.getPatientMaskForVariantInfoFilters(distributableQuery);
			return patientMaskToPatientIdSet(patientMaskForVariantInfoFilters);
        }

		return distributableQuery.getPatientIds();
	}

	private DistributableQuery getDistributableQuery(Query query) {
		DistributableQuery distributableQuery = new DistributableQuery();
		List<Set<Integer>> patientIdSets = new ArrayList<>();

		try {
			query.getAllAnyRecordOf().forEach(anyRecordOfFilterList -> {
				getPatientIdsForAnyRecordOf(anyRecordOfFilterList, distributableQuery).map(patientIdSets::add);
			});
            patientIdSets.addAll(getIdSetsForNumericFilters(query));
            patientIdSets.addAll(getIdSetsForRequiredFields(query, distributableQuery));
            patientIdSets.addAll(getIdSetsForCategoryFilters(query, distributableQuery));
		} catch (InvalidCacheLoadException e) {
			log.warn("Invalid query supplied: " + e.getLocalizedMessage());
			patientIdSets.add(new HashSet<>()); // if an invalid path is supplied, no patients should match.
		}

		Set<Integer> phenotypicPatientSet;
		//AND logic to make sure all patients match each filter
		if(patientIdSets.size()>0) {
			phenotypicPatientSet = applyBooleanLogic(patientIdSets);
		} else {
            // if there are no patient filters, use all patients.
            // todo: we should not have to send these
			phenotypicPatientSet = Arrays.stream(genomicProcessor.getPatientIds())
					.map(String::trim)
					.map(Integer::parseInt)
					.collect(Collectors.toSet());
		}
		distributableQuery.setVariantInfoFilters(query.getVariantInfoFilters());
		distributableQuery.setPatientIds(phenotypicPatientSet);
		return distributableQuery;
	}

	public Set<Integer> patientMaskToPatientIdSet(BigInteger patientMask) {
		Set<Integer> ids = new HashSet<>();
		String bitmaskString = patientMask.toString(2);
		for(int x = 2;x < bitmaskString.length()-2;x++) {
			if('1'==bitmaskString.charAt(x)) {
				String patientId = variantService.getPatientIds()[x-2].trim();
				ids.add(Integer.parseInt(patientId));
			}
		}
		return ids;
	}

	/**
	 * Process each filter in the query and return a list of patient ids that should be included in the
	 * result.
	 *
	 * @param query
	 * @return
	 */
	public Set<Integer> getPatientSubsetForQuery(Query query) {
		Set<Integer> patientIdSet = idSetsForEachFilter(query);

        // todo: make sure this can safely be ignored
		/*TreeSet<Integer> idList;
		if(patientIdSet.isEmpty()) {
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
			idList = new TreeSet<>(applyBooleanLogic(patientIdSet));
		}*/
		return patientIdSet;
	}

	private List<Set<Integer>> getIdSetsForRequiredFields(Query query, DistributableQuery distributableQuery) {
		if(!query.getRequiredFields().isEmpty()) {
            return query.getRequiredFields().stream().map(path -> {
                if (VariantUtils.pathIsVariantSpec(path)) {
                    distributableQuery.addRequiredVariantField(path);
                    return null;
                } else {
                    return new HashSet<Integer>(getCube(path).keyBasedIndex());
                }
            }).filter(Objects::nonNull).collect(Collectors.toList());
        }
        return List.of();
	}

	private Optional<Set<Integer>> getPatientIdsForAnyRecordOf(List<String> anyRecordOfFilters, DistributableQuery distributableQuery) {
		if(!anyRecordOfFilters.isEmpty()) {
			// This is an OR aggregation of anyRecordOf filters
			Set<Integer> anyRecordOfPatientSet = anyRecordOfFilters.parallelStream().flatMap(path -> {
				if (VariantUtils.pathIsVariantSpec(path)) {
					throw new IllegalArgumentException("Variant paths not allowed for anyRecordOf queries");
				}
				return (Stream<Integer>) getCube(path).keyBasedIndex().stream();
			}).collect(Collectors.toSet());
			return Optional.of(anyRecordOfPatientSet);
		}
        return Optional.empty();
	}

	private List<Set<Integer>> getIdSetsForNumericFilters(Query query) {
		if(!query.getNumericFilters().isEmpty()) {
			return query.getNumericFilters().entrySet().stream().map(entry -> {
				Set<Integer> keysForRange = getCube(entry.getKey()).getKeysForRange(entry.getValue().getMin(), entry.getValue().getMax());
				return keysForRange;
			}).collect(Collectors.toList());
		}
        return List.of();
	}

	private List<Set<Integer>> getIdSetsForCategoryFilters(Query query, DistributableQuery distributableQuery) {
		if(!query.getCategoryFilters().isEmpty()) {
			return query.getCategoryFilters().entrySet().stream().map((entry) -> {
				Set<Integer> ids = new TreeSet<>();
				if (VariantUtils.pathIsVariantSpec(entry.getKey())) {
					distributableQuery.addVariantSpecCategoryFilter(entry.getKey(), entry.getValue());
				} else {
					for (String category : entry.getValue()) {
						ids.addAll(getCube(entry.getKey()).getKeysForValue(category));
					}
				}
				return ids;
			}).collect(Collectors.toList());
		}
        return List.of();
	}

	protected Collection<String> getVariantList(Query query) throws IOException {
		DistributableQuery distributableQuery = getDistributableQuery(query);
		return genomicProcessor.processVariantList(distributableQuery);
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
										log.warn("ColumnMeta not found for : [{}]", key);
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
		return genomicProcessor.getPatientIds();
	}

	public Optional<VariantMasks> getMasks(String path, VariantBucketHolder<VariantMasks> variantMasksVariantBucketHolder) {
		return variantService.getMasks(path, variantMasksVariantBucketHolder);
	}

    // todo: handle this locally, we do not want this in the genomic processor
    protected BigInteger createMaskForPatientSet(Set<Integer> patientSubset) {
        return genomicProcessor.createMaskForPatientSet(patientSubset);
    }
}
