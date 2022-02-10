package edu.harvard.hms.dbmi.avillach.hpds.etl.phenotype;

import java.io.*;
import java.util.*;
import java.util.stream.Collectors;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.cache.*;

import edu.harvard.hms.dbmi.avillach.hpds.crypto.Crypto;
import edu.harvard.hms.dbmi.avillach.hpds.data.phenotype.ColumnMeta;
import edu.harvard.hms.dbmi.avillach.hpds.data.phenotype.KeyAndValue;
import edu.harvard.hms.dbmi.avillach.hpds.data.phenotype.PhenoCube;
 
/**
 * This class provides similar functioanlity to the LoadingStore class, but is designed with sequential loading in mind.
 * 
 * This will write out partial cubes to individual files instead of assuming that they can immediately be 
 * sequenced into the allObservationStore;  This will allow us to pick them back up and add more patients 
 * to existing stores so that we can better handle fragmented data.
 * 
 * 
 * @author nchu
 *
 */
public class SequentialLoadingStore {
 
	private static final String COLUMNMETA_FILENAME = "/opt/local/hpds/columnMeta.javabin";
	protected static final String OBSERVATIONS_FILENAME = "/opt/local/hpds/allObservationsStore.javabin";
	protected static final String OBS_TEMP_FILENAME = "/opt/local/hpds/allObservationsTemp.javabin";
	
	public RandomAccessFile allObservationsStore;
	public RandomAccessFile allObservationsTemp;

	TreeMap<String, ColumnMeta> metadataMap = new TreeMap<>();
	
	private static Logger log = LoggerFactory.getLogger(SequentialLoadingStore.class);
	
	public SequentialLoadingStore() {
		try {
			allObservationsTemp = new RandomAccessFile(OBS_TEMP_FILENAME, "rw");
			
		} catch (FileNotFoundException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		
	}
	
	public LoadingCache<String, PhenoCube> store = CacheBuilder.newBuilder()
			.maximumSize(16)
			.removalListener(new RemovalListener<String, PhenoCube>() {

				@Override
				public void onRemoval(RemovalNotification<String, PhenoCube> cubeRemoval) {
					log.info("removing " + cubeRemoval.getKey());
					if(cubeRemoval.getValue().getLoadingMap()!=null) {
//						complete(cubeRemoval.getValue());
//					}
						try {
							ColumnMeta columnMeta = new ColumnMeta().setName(cubeRemoval.getKey()).setWidthInBytes(cubeRemoval.getValue().getColumnWidth()).setCategorical(cubeRemoval.getValue().isStringType());
	
							columnMeta.setAllObservationsOffset(allObservationsTemp.getFilePointer());
							columnMeta.setObservationCount(cubeRemoval.getValue().sortedByKey().length);
							columnMeta.setPatientCount(Arrays.stream(cubeRemoval.getValue().sortedByKey()).map((kv)->{return kv.getKey();}).collect(Collectors.toSet()).size());
							if(columnMeta.isCategorical()) {
								columnMeta.setCategoryValues(new ArrayList<String>(new TreeSet<String>(cubeRemoval.getValue().keyBasedArray())));
							} else {
								List<Double> map = (List<Double>) cubeRemoval.getValue().keyBasedArray().stream().map((value)->{return (Double) value;}).collect(Collectors.toList());
								double min = Double.MAX_VALUE;
								double max = Double.MIN_VALUE;
								for(double f : map) {
									min = Double.min(min, f);
									max = Double.max(max, f);
								}
								columnMeta.setMin(min);
								columnMeta.setMax(max);
							}
							ByteArrayOutputStream byteStream = new ByteArrayOutputStream();
							try {
	
								ObjectOutputStream out = new ObjectOutputStream(byteStream);
								out.writeObject(cubeRemoval.getValue().getLoadingMap());
								out.flush();
								out.close();
							} catch (IOException e) {
								e.printStackTrace();
							}
							allObservationsTemp.write(Crypto.encryptData(byteStream.toByteArray()));
							columnMeta.setAllObservationsLength(allObservationsTemp.getFilePointer());
							metadataMap.put(columnMeta.getName(), columnMeta);
						} catch (IOException e1) {
							e1.printStackTrace();
						}
					}
				}
			})
			.build(
					new CacheLoader<String, PhenoCube>() {
						public PhenoCube load(String key) throws Exception {
							ColumnMeta columnMeta = metadataMap.get(key);
							if(columnMeta != null) {
								log.info("Loading concept : [" + key + "]");
								allObservationsTemp.seek(columnMeta.getAllObservationsOffset());
								int length = (int) (columnMeta.getAllObservationsLength() - columnMeta.getAllObservationsOffset());
								byte[] buffer = new byte[length];
								allObservationsTemp.read(buffer);
								allObservationsTemp.seek(allObservationsTemp.length());
								ObjectInputStream inStream = new ObjectInputStream(new ByteArrayInputStream(Crypto.decryptData(buffer)));
								
								PhenoCube cube = new PhenoCube(key, columnMeta.isCategorical() ? String.class : Double.class);
								cube.setLoadingMap((List<KeyAndValue>)inStream.readObject());
								cube.setColumnWidth(columnMeta.getWidthInBytes());
								inStream.close();
								
								return cube;																		
							}else {
								log.info("creating new concept : [" + key + "]");
								return null;
							}
						}
					});

	public TreeSet<Integer> allIds = new TreeSet<Integer>();
	
	public void saveStore() throws FileNotFoundException, IOException, ClassNotFoundException {
		System.out.println("flushing temp storage");
		store.invalidateAll();
		store.cleanUp();
		
		
		allObservationsStore = new RandomAccessFile(OBSERVATIONS_FILENAME, "rw");
		//we dumped it all in a temp file;  now sort all the data and compress it into the real Store
		for(String concept : metadataMap.keySet()) {
			ColumnMeta columnMeta = metadataMap.get(concept);
			
			log.debug("Writing concept : [" + concept + "]");
			allObservationsTemp.seek(columnMeta.getAllObservationsOffset());
			int length = (int) (columnMeta.getAllObservationsLength() - columnMeta.getAllObservationsOffset());
			byte[] buffer = new byte[length];
			allObservationsTemp.read(buffer);
			allObservationsTemp.seek(allObservationsTemp.length());
			ObjectInputStream inStream = new ObjectInputStream(new ByteArrayInputStream(Crypto.decryptData(buffer)));
			
			PhenoCube cube = new PhenoCube(concept, columnMeta.isCategorical() ? String.class : Double.class);
			cube.setLoadingMap((List<KeyAndValue>)inStream.readObject());
			cube.setColumnWidth(columnMeta.getWidthInBytes());
			inStream.close();
			
			complete(cube);
			write(columnMeta, cube);
		}
		allObservationsStore.close();
		
		System.out.println("Writing metadata");
		ObjectOutputStream metaOut = new ObjectOutputStream(new GZIPOutputStream(new FileOutputStream(new File(COLUMNMETA_FILENAME))));
		metaOut.writeObject(metadataMap);
		metaOut.writeObject(allIds);
		metaOut.flush();
		metaOut.close();
		System.out.println("Closing Store");
	
	}
	
	private void write(ColumnMeta columnMeta, PhenoCube cube) throws IOException {
		columnMeta.setAllObservationsOffset(allObservationsStore.getFilePointer());
		columnMeta.setObservationCount(cube.sortedByKey().length);
		columnMeta.setPatientCount(Arrays.stream(cube.sortedByKey()).map((kv)->{return kv.getKey();}).collect(Collectors.toSet()).size());
		if(columnMeta.isCategorical()) {
			columnMeta.setCategoryValues(new ArrayList<String>(new TreeSet<String>(cube.keyBasedArray())));
		} else {
			List<Double> map = (List<Double>) cube.keyBasedArray().stream().map((value)->{return (Double) value;}).collect(Collectors.toList());
			double min = Double.MAX_VALUE;
			double max = Double.MIN_VALUE;
			for(double f : map) {
				min = Double.min(min, f);
				max = Double.max(max, f);
			}
			columnMeta.setMin(min);
			columnMeta.setMax(max);
		}
		ByteArrayOutputStream byteStream = new ByteArrayOutputStream();
		try {

			ObjectOutputStream out = new ObjectOutputStream(byteStream);
			out.writeObject(cube);
			out.flush();
			out.close();
		} catch (IOException e) {
			e.printStackTrace();
		}
		allObservationsStore.write(Crypto.encryptData(byteStream.toByteArray()));
		columnMeta.setAllObservationsLength(allObservationsStore.getFilePointer());
	}

	private <V extends Comparable<V>> void complete(PhenoCube<V> cube) {
		ArrayList<KeyAndValue<V>> entryList = new ArrayList<KeyAndValue<V>>(
				cube.getLoadingMap().stream().map((entry)->{
					return new KeyAndValue<V>(entry.getKey(), entry.getValue(), entry.getTimestamp());
				}).collect(Collectors.toList()));

		List<KeyAndValue<V>> sortedByKey = entryList.stream()
				.sorted(Comparator.comparing(KeyAndValue<V>::getKey))
				.collect(Collectors.toList());
		cube.setSortedByKey(sortedByKey.toArray(new KeyAndValue[0]));

		if(cube.isStringType()) {
			TreeMap<V, List<Integer>> categoryMap = new TreeMap<>();
			for(KeyAndValue<V> entry : cube.sortedByValue()) {
				if(!categoryMap.containsKey(entry.getValue())) {
					categoryMap.put(entry.getValue(), new LinkedList<Integer>());
				}
				categoryMap.get(entry.getValue()).add(entry.getKey());
			}
			TreeMap<V, TreeSet<Integer>> categorySetMap = new TreeMap<>();
			categoryMap.entrySet().stream().forEach((entry)->{
				categorySetMap.put(entry.getKey(), new TreeSet<Integer>(entry.getValue()));
			});
			cube.setCategoryMap(categorySetMap);
		}
		
	}

	public void dumpStats() {
		System.out.println("Dumping Stats");
		try (ObjectInputStream objectInputStream = new ObjectInputStream(new GZIPInputStream(new FileInputStream(COLUMNMETA_FILENAME)));){
			TreeMap<String, ColumnMeta> metastore = (TreeMap<String, ColumnMeta>) objectInputStream.readObject();
			Set<Integer> allIds = (TreeSet<Integer>) objectInputStream.readObject();

			long totalNumberOfObservations = 0;
			
			System.out.println("\n\nConceptPath\tObservationCount\tMinNumValue\tMaxNumValue\tCategoryValues");
			for(String key : metastore.keySet()) {
				ColumnMeta columnMeta = metastore.get(key);
				System.out.println(String.join("\t", key.toString(), columnMeta.getObservationCount()+"", 
						columnMeta.getMin()==null ? "NaN" : columnMeta.getMin().toString(), 
								columnMeta.getMax()==null ? "NaN" : columnMeta.getMax().toString(), 
										columnMeta.getCategoryValues() == null ? "NUMERIC CONCEPT" : String.join(",", 
												columnMeta.getCategoryValues()
												.stream().map((value)->{return value==null ? "NULL_VALUE" : "\""+value+"\"";}).collect(Collectors.toList()))));
				totalNumberOfObservations += columnMeta.getObservationCount();
			}

			System.out.println("Total Number of Concepts : " + metastore.size());
			System.out.println("Total Number of Patients : " + allIds.size());
			System.out.println("Total Number of Observations : " + totalNumberOfObservations);
			
		} catch (IOException | ClassNotFoundException e) {
			e.printStackTrace();
			throw new RuntimeException("Could not load metastore");
		}
	}


}
