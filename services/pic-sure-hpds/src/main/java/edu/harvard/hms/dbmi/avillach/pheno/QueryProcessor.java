package edu.harvard.hms.dbmi.avillach.pheno;

import java.io.ByteArrayInputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;

import org.apache.log4j.Logger;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;

import edu.harvard.hms.dbmi.avillach.pheno.crypto.Crypto;
import edu.harvard.hms.dbmi.avillach.pheno.data.AsyncResult;
import edu.harvard.hms.dbmi.avillach.pheno.data.ColumnMeta;
import edu.harvard.hms.dbmi.avillach.pheno.data.KeyAndValue;
import edu.harvard.hms.dbmi.avillach.pheno.data.PhenoCube;
import edu.harvard.hms.dbmi.avillach.pheno.data.Query;
import edu.harvard.hms.dbmi.avillach.pheno.data.Filter.FloatFilter;
import edu.harvard.hms.dbmi.avillach.pheno.store.NotEnoughMemoryException;
import edu.harvard.hms.dbmi.avillach.pheno.store.ResultStore;

public class QueryProcessor {

	private Logger log = Logger.getLogger(QueryProcessor.class);

	private final int ID_BATCH_SIZE;

	private final int CACHE_SIZE;

	private static LoadingCache<String, PhenoCube<?>> store;

	private static TreeMap<String, ColumnMeta> metaStore;

	private TreeSet<Integer> allIds;

	public QueryProcessor() {
		CACHE_SIZE = Integer.parseInt(System.getProperty("CACHE_SIZE"));
		ID_BATCH_SIZE = Integer.parseInt(System.getProperty("ID_BATCH_SIZE"));
		store = initializeCache();
		metaStore = loadMetaStore();
//		loadAllDataFiles();
//		initAllIds();
	}

	private TreeMap<String, ColumnMeta> loadMetaStore() {
		try (ObjectInputStream objectInputStream = new ObjectInputStream(new FileInputStream("/opt/local/phenocube/columnMeta.javabin"));){
			TreeMap<String, ColumnMeta> readObject = (TreeMap<String, ColumnMeta>) objectInputStream.readObject();
			return readObject;
		} catch (IOException | ClassNotFoundException e) {
			e.printStackTrace();
			throw new RuntimeException("Could not load metastore");
		}
	}

	public void runQuery(Query query, AsyncResult result) throws NotEnoughMemoryException {
		ArrayList<Set<Integer>> filteredIdSets = idSetsForEachFilter(query);

		TreeSet<Integer> idList;
		if(filteredIdSets.isEmpty()) {
			idList = allIds;
		}else {
			idList = new TreeSet<Integer>(applyBooleanLogic(filteredIdSets));
		}
		log.info("Processing " + idList.size() + " rows for result " + result.id);
		for(List<Integer> list : Lists.partition(new ArrayList<>(idList), ID_BATCH_SIZE)){
			result.stream.appendResultStore(buildResult(result, query, new TreeSet<Integer>(list)));			
		};
	}

	private Set<Integer> applyBooleanLogic(ArrayList<Set<Integer>> filteredIdSets) {
		Set<Integer>[] ids = new Set[] {filteredIdSets.get(0)};
		filteredIdSets.forEach((keySet)->{
			ids[0] = Sets.intersection(ids[0], keySet);
		});
		return ids[0];
	}

	private ArrayList<Set<Integer>> idSetsForEachFilter(Query query) {
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
				String[] categoryFilter = query.categoryFilters.get(key);
				Set<Integer> ids = new TreeSet<Integer>();
				for(String category : categoryFilter) {
					ids.addAll(getCube(key).getKeysForValue(category));
				}
				return ids;
			}).collect(Collectors.toSet());
			filteredIdSets.addAll(idsThatMatchFilters);
		}
		return filteredIdSets;
	}

	private ResultStore buildResult(AsyncResult result, Query query, TreeSet<Integer> ids) throws NotEnoughMemoryException {
		List<String> paths = query.fields;
		int columnCount = paths.size() + 1;

		ArrayList<Integer> columnIndex = useResidentCubesFirst(paths, columnCount);
		ResultStore results = new ResultStore(result, query.id, paths.stream().map((path)->{
			return metaStore.get(path);
		}).collect(Collectors.toList()), ids);

		columnIndex.parallelStream().forEach((column)->{
			processColumn(paths, ids, results, column);
		});

		return results;
	}

	private ArrayList<Integer> useResidentCubesFirst(List<String> paths, int columnCount) {
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

	private void processColumn(List<String> paths, TreeSet<Integer> ids, ResultStore results,
			Integer x) {
		try{
			PhenoCube<?> cube = getCube(paths.get(x-1));

			KeyAndValue<?>[] cubeValues = cube.sortedByKey();

			int idPointer = 0;

			ByteBuffer floatBuffer = ByteBuffer.allocate(Float.BYTES);
			int idInSubsetPointer = 0;
			for(int id : ids) {
				while(idPointer < cubeValues.length) {
					int key = cubeValues[idPointer].getKey();
					if(key < id) {
						idPointer++;	
					} else if(key == id){
						idPointer = writeResultField(results, x, cube, cubeValues, idPointer, floatBuffer,
								idInSubsetPointer);
						break;
					} else {
						writeNullResultField(results, x, cube, floatBuffer, idInSubsetPointer);
						break;
					}
				}
				idInSubsetPointer++;
			}
		}catch(Exception e) {
			e.printStackTrace();
			return;
		}

	}

	private int writeResultField(ResultStore results, Integer x, PhenoCube<?> cube, KeyAndValue<?>[] cubeValues,
			int idPointer, ByteBuffer floatBuffer, int idInSubsetPointer) {
		byte[] valueBuffer;
		Comparable<?> value = cubeValues[idPointer++].getValue();
		if(cube.isStringType()) {
			valueBuffer = value.toString().getBytes();
		}else {
			valueBuffer = floatBuffer.putFloat((Float)value).array();
			floatBuffer.clear();
		}
		results.writeField(x,idInSubsetPointer, valueBuffer);
		return idPointer;
	}

	private void writeNullResultField(ResultStore results, Integer x, PhenoCube<?> cube, ByteBuffer floatBuffer, int idInSubsetPointer) {
		byte[] valueBuffer = null;
		if(cube.isStringType()) {
			valueBuffer = "".getBytes();
		}else {
			Float nullFloat = Float.NaN;
			valueBuffer = floatBuffer.putFloat(nullFloat).array();
			floatBuffer.clear();
		}
		results.writeField(x,idInSubsetPointer, valueBuffer);
	}

	@SuppressWarnings("rawtypes")
	private PhenoCube getCube(String path) {
		try {
			return store.get(path);
		} catch (ExecutionException e) {
			throw new RuntimeException(e);
		}
	}

	private LoadingCache<String, PhenoCube<?>> initializeCache() {
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

	void loadAllDataFiles() {
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

	@SuppressWarnings("unchecked") 
	void initAllIds() {
		if(Crypto.hasKey()) {
			// NHANES
			allIds = new TreeSet<Integer>(getCube("\\demographics\\AGE\\").keyBasedIndex());
			// COPDgene
			//		allIds = new TreeSet<Integer>(getCube("\\01 Demographics\\Age at enrollment\\").keyBasedIndex());
		}
	}

	public TreeMap<String, ColumnMeta> getDictionary() {
		return metaStore;
	}
}
