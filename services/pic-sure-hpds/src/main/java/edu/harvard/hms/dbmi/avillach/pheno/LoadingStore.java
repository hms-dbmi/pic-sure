package edu.harvard.hms.dbmi.avillach.pheno;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
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
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;

import org.apache.log4j.Logger;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.cache.RemovalListener;
import com.google.common.cache.RemovalNotification;

import edu.harvard.hms.dbmi.avillach.pheno.crypto.Crypto;
import edu.harvard.hms.dbmi.avillach.pheno.data.ColumnMeta;
import edu.harvard.hms.dbmi.avillach.pheno.data.KeyAndValue;
import edu.harvard.hms.dbmi.avillach.pheno.data.PhenoCube;

public class LoadingStore {

	private static HashMap<String, byte[]> compressedPhenoCubes = new HashMap<>();

	static RandomAccessFile allObservationsStore;

	static TreeMap<String, ColumnMeta> metadataMap = new TreeMap<>();

	private static Logger log = Logger.getLogger(LoadingStore.class);
	
	public static LoadingCache<String, PhenoCube> store = CacheBuilder.newBuilder()
			.maximumSize(2000)
			.removalListener(new RemovalListener<String, PhenoCube>() {

				@Override
				public void onRemoval(RemovalNotification<String, PhenoCube> arg0) {
					log.info("removing " + arg0.getKey());
					complete(arg0.getValue());
					try {
						ColumnMeta columnMeta = metadataMap.get(arg0.getKey());
						columnMeta.setAllObservationsOffset(allObservationsStore.getFilePointer());
						columnMeta.setObservationCount(arg0.getValue().sortedByKey().length);
						if(columnMeta.isCategorical()) {
							columnMeta.setCategoryValues(new ArrayList<String>());
							columnMeta.getCategoryValues().addAll(new TreeSet<String>(arg0.getValue().keyBasedArray()));
						} else {
							List<Float> map = (List<Float>) arg0.getValue().keyBasedArray().stream().map((value)->{return (Float) value;}).collect(Collectors.toList());
							float min = Float.MAX_VALUE;
							float max = Float.MIN_VALUE;
							for(float f : map) {
								min = Float.min(min, f);
								max = Float.max(max, f);
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
						compressedPhenoCubes.put(arg0.getKey(), byteStream.toByteArray());
					} catch (IOException e1) {
						e1.printStackTrace();
					}
				}

				private <V extends Comparable<V>> void complete(PhenoCube<V> cube) {
					ArrayList<KeyAndValue<V>> entryList = new ArrayList<KeyAndValue<V>>(
							cube.loadingMap.entrySet().stream().map((entry)->{
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
							log.info(key);
							byte[] bytes = compressedPhenoCubes.get(key);
							if(bytes == null) return null;
							ObjectInputStream inStream = new ObjectInputStream(new ByteArrayInputStream(bytes));
							PhenoCube ret = (PhenoCube)inStream.readObject();
							inStream.close();
							return ret;
						}
					});
	
	public static void saveStore() throws FileNotFoundException, IOException {
		store.asMap().forEach((String key, PhenoCube value)->{
			metadataMap.put(key, new ColumnMeta().setName(key).setWidthInBytes(value.getColumnWidth()).setCategorical(value.isStringType()));
		});
		store.invalidateAll();
		ObjectOutputStream metaOut = new ObjectOutputStream(new FileOutputStream(new File("/opt/local/phenocube/columnMeta.javabin")));
		metaOut.writeObject(metadataMap);
		metaOut.flush();
		metaOut.close();
		allObservationsStore.close();
	}


}
