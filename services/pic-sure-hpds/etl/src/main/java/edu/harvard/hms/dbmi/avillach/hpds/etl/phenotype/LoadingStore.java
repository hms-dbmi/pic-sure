package edu.harvard.hms.dbmi.avillach.hpds.etl.phenotype;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import org.apache.log4j.Logger;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.cache.RemovalListener;
import com.google.common.cache.RemovalNotification;

import edu.harvard.hms.dbmi.avillach.hpds.crypto.Crypto;
import edu.harvard.hms.dbmi.avillach.hpds.data.phenotype.ColumnMeta;
import edu.harvard.hms.dbmi.avillach.hpds.data.phenotype.KeyAndValue;
import edu.harvard.hms.dbmi.avillach.hpds.data.phenotype.PhenoCube;
 
public class LoadingStore {
 
	RandomAccessFile allObservationsStore;

	TreeMap<String, ColumnMeta> metadataMap = new TreeMap<>();
	
	private static Logger log = Logger.getLogger(LoadingStore.class);
	
	public LoadingCache<String, PhenoCube> store = CacheBuilder.newBuilder()
			.maximumSize(64)
			.removalListener(new RemovalListener<String, PhenoCube>() {

				@Override
				public void onRemoval(RemovalNotification<String, PhenoCube> arg0) {
					log.info("removing " + arg0.getKey());
					complete(arg0.getValue());
					try {
						ColumnMeta columnMeta = new ColumnMeta().setName(arg0.getKey()).setWidthInBytes(arg0.getValue().getColumnWidth()).setCategorical(arg0.getValue().isStringType());

						columnMeta.setAllObservationsOffset(allObservationsStore.getFilePointer());
						columnMeta.setObservationCount(arg0.getValue().sortedByKey().length);
						if(columnMeta.isCategorical()) {
							columnMeta.setCategoryValues(new ArrayList<String>(new TreeSet<String>(arg0.getValue().keyBasedArray())));
						} else {
							List<Double> map = (List<Double>) arg0.getValue().keyBasedArray().stream().map((value)->{return (Double) value;}).collect(Collectors.toList());
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
							out.writeObject(arg0.getValue());
							out.flush();
							out.close();
						} catch (IOException e) {
							e.printStackTrace();
						}
						allObservationsStore.write(Crypto.encryptData(byteStream.toByteArray()));
						columnMeta.setAllObservationsLength(allObservationsStore.getFilePointer());
						metadataMap.put(columnMeta.getName(), columnMeta);
					} catch (IOException e1) {
						e1.printStackTrace();
					}
				}

				private <V extends Comparable<V>> void complete(PhenoCube<V> cube) {
					ArrayList<KeyAndValue<V>> entryList = new ArrayList<KeyAndValue<V>>(
							cube.getLoadingMap().stream().map((entry)->{
								return new KeyAndValue<V>(entry.getKey(), entry.getValue());
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
			})
			.build(
					new CacheLoader<String, PhenoCube>() {
						public PhenoCube load(String key) throws Exception {
							log.info("Loading cube: " + key);
							return null;
						}
					});

	TreeSet<Integer> allIds = new TreeSet<Integer>();
	
	public void saveStore() throws FileNotFoundException, IOException {
		System.out.println("Invalidating store");
		store.invalidateAll();
		store.cleanUp();
		System.out.println("Writing metadata");
		ObjectOutputStream metaOut = new ObjectOutputStream(new GZIPOutputStream(new FileOutputStream(new File("/opt/local/hpds/columnMeta.javabin"))));
		metaOut.writeObject(metadataMap);
		metaOut.writeObject(allIds);
		metaOut.flush();
		metaOut.close();
		System.out.println("Closing Store");
		allObservationsStore.close();
	}

	public void dumpStats() {
		System.out.println("Dumping Stats");
		try (ObjectInputStream objectInputStream = new ObjectInputStream(new GZIPInputStream(new FileInputStream("/opt/local/hpds/columnMeta.javabin")));){
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
