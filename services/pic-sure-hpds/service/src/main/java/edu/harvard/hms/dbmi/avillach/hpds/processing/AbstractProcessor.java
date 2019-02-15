package edu.harvard.hms.dbmi.avillach.hpds.processing;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.RandomAccessFile;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.Sets;

import edu.harvard.hms.dbmi.avillach.hpds.crypto.Crypto;
import edu.harvard.hms.dbmi.avillach.hpds.data.genotype.VariantMasks;
import edu.harvard.hms.dbmi.avillach.hpds.data.genotype.VariantStore;
import edu.harvard.hms.dbmi.avillach.hpds.data.phenotype.ColumnMeta;
import edu.harvard.hms.dbmi.avillach.hpds.data.phenotype.PhenoCube;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.AsyncResult;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.Filter.FloatFilter;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.Processor;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.Query;
import edu.harvard.hms.dbmi.avillach.hpds.exception.NotEnoughMemoryException;

public abstract class AbstractProcessor implements Processor{

	private static final String HOMOZYGOUS_VARIANT = "1/1";

	private static final String HETEROZYGOUS_VARIANT = "0/1";

	private static final String HETEROZYGOUS_REFERENCE = "0/0";

	private Logger log = LoggerFactory.getLogger(AbstractProcessor.class);

	static {
		CACHE_SIZE = Integer.parseInt(System.getProperty("CACHE_SIZE"));
		ID_BATCH_SIZE = Integer.parseInt(System.getProperty("ID_BATCH_SIZE"));
		ALL_IDS_CONCEPT = System.getProperty("ALL_IDS_CONCEPT");
	}

	protected static int ID_BATCH_SIZE;

	protected static int CACHE_SIZE;

	protected static String ALL_IDS_CONCEPT;

	protected static LoadingCache<String, PhenoCube<?>> store;

	protected static VariantStore variantStore;

	protected static TreeMap<String, ColumnMeta> metaStore;

	protected static TreeSet<Integer> allIds;

	protected TreeMap<String, ColumnMeta> loadMetaStore() {
		try (ObjectInputStream objectInputStream = new ObjectInputStream(new FileInputStream("/opt/local/phenocube/columnMeta.javabin"));){
			TreeMap<String, ColumnMeta> readObject = (TreeMap<String, ColumnMeta>) objectInputStream.readObject();
			return readObject;
		} catch (IOException | ClassNotFoundException e) {
			e.printStackTrace();
			throw new RuntimeException("Could not load metastore");
		} 
	}

	protected Set<Integer> applyBooleanLogic(ArrayList<Set<Integer>> filteredIdSets) {
		Set<Integer>[] ids = new Set[] {filteredIdSets.get(0)};
		filteredIdSets.forEach((keySet)->{
			ids[0] = Sets.intersection(ids[0], keySet);
		});
		return ids[0];
	}

	protected ArrayList<Set<Integer>> idSetsForEachFilter(Query query) {
		ArrayList<Set<Integer>> filteredIdSets = new ArrayList<Set<Integer>>();
		if(query.requiredFields != null && !query.requiredFields.isEmpty()) {
			filteredIdSets.addAll((Set<TreeSet<Integer>>)(query.requiredFields.parallelStream().map(path->{
				return new TreeSet<Integer>(getCube(path).keyBasedIndex()) ;
			}).collect(Collectors.toSet()))); 
		}
		if(query.numericFilters != null && !query.numericFilters.isEmpty()) {
			filteredIdSets.addAll((Set<TreeSet<Integer>>)(query.numericFilters.keySet().parallelStream().map((String key)->{
				FloatFilter FloatFilter = query.numericFilters.get(key);
				return (TreeSet<Integer>)(getCube(key).getKeysForRange(FloatFilter.getMin(), FloatFilter.getMax()));
			}).collect(Collectors.toSet())));
		}
		if(query.categoryFilters != null && !query.categoryFilters.isEmpty()) {
			Set<Set<Integer>> idsThatMatchFilters = (Set<Set<Integer>>)query.categoryFilters.keySet().parallelStream().map((String key)->{
				Set<Integer> ids = new TreeSet<Integer>();
				if(pathIsVariantSpec(key)) {
					ArrayList<BigInteger> variantBitmasks = new ArrayList<>();
					Arrays.stream(query.categoryFilters.get(key)).forEach((zygosity) -> {
						String variantName = key.replaceAll(",\\d/\\d$", "");
						VariantMasks masks;
						try {
							masks = variantStore.getMasks(variantName);
							if(masks!=null) {
								if(zygosity.equals(HETEROZYGOUS_REFERENCE)) {
									BigInteger indiscriminateVariantBitmap = masks.heterozygousMask.or(masks.homozygousMask);
									for(int x = 2;x<indiscriminateVariantBitmap.bitLength()-2;x++) {
										indiscriminateVariantBitmap = indiscriminateVariantBitmap.flipBit(x);
									}
									log.debug("Indisc : " + indiscriminateVariantBitmap.toString(2));
									variantBitmasks.add(indiscriminateVariantBitmap);
								} else if(zygosity.equals(HETEROZYGOUS_VARIANT)) {
									BigInteger heterozygousVariantBitmap = masks.heterozygousMask;
									log.debug("HETEROZYGOUS_VARIANT" + heterozygousVariantBitmap.toString(2));
									variantBitmasks.add(heterozygousVariantBitmap);							
								}else if(zygosity.equals(HOMOZYGOUS_VARIANT)) {
									BigInteger homozygousVariantBitmap = masks.homozygousMask;
									log.debug(HOMOZYGOUS_VARIANT + homozygousVariantBitmap.toString(2));
									variantBitmasks.add(homozygousVariantBitmap);
								}else if(zygosity.equals("")) {
									BigInteger indiscriminateVariantBitmap = masks.heterozygousMask.or(masks.homozygousMask);
									log.debug("Indiscriminate : " + indiscriminateVariantBitmap.toString(2));
									variantBitmasks.add(indiscriminateVariantBitmap);
								}
							} else {
								variantBitmasks.add(variantStore.emptyBitmask);
							}
						} catch (IOException e) {
							// TODO Auto-generated catch block
							e.printStackTrace();
						}
					});
					BigInteger bitmask = variantBitmasks.get(0);
					if(variantBitmasks.size()>1) {
						for(int x = 1;x<variantBitmasks.size();x++) {
							bitmask = bitmask.or(variantBitmasks.get(x));
						}
					}
					String bitmaskString = bitmask.toString(2);
					System.out.println("or'd masks : " + bitmaskString);
					for(int x = 2;x < bitmaskString.length()-2;x++) {
						if('1'==bitmaskString.charAt(x)) {
							String patientId = variantStore.getPatientIds()[x-2]; 
							ids.add(Integer.parseInt(patientId));
						}
					}
				}else {
					String[] categoryFilter = query.categoryFilters.get(key);
					for(String category : categoryFilter) {
						ids.addAll(getCube(key).getKeysForValue(category));
					}
				}
				return ids;
			}).collect(Collectors.toSet());
			filteredIdSets.addAll(idsThatMatchFilters);
		}
		return filteredIdSets;
	}

	protected boolean pathIsVariantSpec(String key) {
		return key.matches("rs[0-9]+.*") || key.matches("[0-9]+,[0-9\\.]+,.*");
	}

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

	protected LoadingCache<String, PhenoCube<?>> initializeCache() throws ClassNotFoundException, FileNotFoundException, IOException {
		if(new File("/opt/local/hpds/all/variantStore.javabin").exists()) {
			variantStore = (VariantStore) new ObjectInputStream(new FileInputStream("/opt/local/hpds/all/variantStore.javabin")).readObject();
			variantStore.open();			
		}
		return CacheBuilder.newBuilder()
				.maximumSize(CACHE_SIZE)
				.build(
						new CacheLoader<String, PhenoCube<?>>() {
							public PhenoCube<?> load(String key) throws Exception {
								RandomAccessFile allObservationsStore = new RandomAccessFile("/opt/local/phenocube/allObservationsStore.javabin", "r");
								ColumnMeta columnMeta = metaStore.get(key);
								allObservationsStore.seek(columnMeta.getAllObservationsOffset());
								int length = (int) (columnMeta.getAllObservationsLength() - columnMeta.getAllObservationsOffset());
								byte[] buffer = new byte[length];
								allObservationsStore.read(buffer);
								allObservationsStore.close();
								ObjectInputStream inStream = new ObjectInputStream(new ByteArrayInputStream(Crypto.decryptData(buffer)));
								PhenoCube<?> ret = (PhenoCube<?>)inStream.readObject();
								inStream.close();
								return ret;									
							}
						});
	}

	public void loadAllDataFiles() {
		if(Crypto.hasKey()) {
			List<String> cubes = new ArrayList<String>(metaStore.keySet());
			for(int x = 0;x<Math.min(metaStore.size(), CACHE_SIZE);x++){
				try {
					if(metaStore.get(cubes.get(x)).getObservationCount() == 0){
						log.info("Rejecting : " + cubes.get(x) + " because it has no entries.");
					}else {
						store.get(cubes.get(x));
						log.info("loaded: " + cubes.get(x));					
					}
				} catch (ExecutionException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}

			}
		}
	}

	@SuppressWarnings("rawtypes")
	protected PhenoCube getCube(String path) {
		try { 
			return store.get(path);
		} catch (ExecutionException e) {
			throw new RuntimeException(e);
		}
	}

	@SuppressWarnings("unchecked") 
	public void initAllIds() {
		if(Crypto.hasKey()) {
			allIds = new TreeSet<Integer>(getCube(ALL_IDS_CONCEPT).keyBasedIndex());
		}
	}

	public TreeMap<String, ColumnMeta> getDictionary() {
		return metaStore;
	}

	public abstract void runQuery(Query query, AsyncResult asyncResult) throws NotEnoughMemoryException;

}
