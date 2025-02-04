package edu.harvard.hms.dbmi.avillach.hpds.etl;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.cache.*;

import edu.harvard.hms.dbmi.avillach.hpds.crypto.Crypto;
import edu.harvard.hms.dbmi.avillach.hpds.data.phenotype.ColumnMeta;
import edu.harvard.hms.dbmi.avillach.hpds.data.phenotype.KeyAndValue;
import edu.harvard.hms.dbmi.avillach.hpds.data.phenotype.PhenoCube;
 
public class LoadingStore {
 
	public RandomAccessFile allObservationsStore;

	TreeMap<String, ColumnMeta> metadataMap = new TreeMap<>();
	
	private static Logger log = LoggerFactory.getLogger(LoadingStore.class);
	
	public LoadingCache<String, PhenoCube> store = CacheBuilder.newBuilder()
			.maximumSize(16)
			.removalListener(new RemovalListener<String, PhenoCube>() {

				@Override
				public void onRemoval(RemovalNotification<String, PhenoCube> arg0) {
					log.info("removing " + arg0.getKey());
					if(arg0.getValue().getLoadingMap()!=null) {
						complete(arg0.getValue());
					}
					try {
						ColumnMeta columnMeta = new ColumnMeta().setName(arg0.getKey()).setWidthInBytes(arg0.getValue().getColumnWidth()).setCategorical(arg0.getValue().isStringType());

						columnMeta.setAllObservationsOffset(allObservationsStore.getFilePointer());
						columnMeta.setObservationCount(arg0.getValue().sortedByKey().length);
						columnMeta.setPatientCount(Arrays.stream(arg0.getValue().sortedByKey()).map((kv)->{return kv.getKey();}).collect(Collectors.toSet()).size());
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

						ObjectOutputStream out = new ObjectOutputStream(byteStream);
						out.writeObject(arg0.getValue());
						out.flush();
						out.close();

						allObservationsStore.write(Crypto.encryptData(byteStream.toByteArray()));
						columnMeta.setAllObservationsLength(allObservationsStore.getFilePointer());
						metadataMap.put(columnMeta.getName(), columnMeta);
					} catch (IOException e) {
						throw new UncheckedIOException(e);
					}
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
			})
			.build(
					new CacheLoader<String, PhenoCube>() {
						public PhenoCube load(String key) throws Exception {
							log.info("Loading cube: " + key);
							return null;
						}
					});

	public TreeSet<Integer> allIds = new TreeSet<Integer>();
	
	public void saveStore(String hpdsDirectory) throws IOException {
		System.out.println("Invalidating store");
		store.invalidateAll();
		store.cleanUp();
		System.out.println("Writing metadata");
		ObjectOutputStream metaOut = new ObjectOutputStream(new GZIPOutputStream(new FileOutputStream(hpdsDirectory + "columnMeta.javabin")));
		metaOut.writeObject(metadataMap);
		metaOut.writeObject(allIds);
		metaOut.flush();
		metaOut.close();
		System.out.println("Closing Store");
		allObservationsStore.close();
		dumpStatsAndColumnMeta(hpdsDirectory);
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
			throw new RuntimeException("Could not load metastore");
		}
	}

	/**
	 * This method will display counts for the objects stored in the metadata.
	 * This will also write out a csv file used by the data dictionary importer.
	 */
	public void dumpStatsAndColumnMeta(String hpdsDirectory) {
		try (ObjectInputStream objectInputStream =
					 new ObjectInputStream(new GZIPInputStream(new FileInputStream(hpdsDirectory + "columnMeta.javabin")))){
			TreeMap<String, ColumnMeta> metastore = (TreeMap<String, ColumnMeta>) objectInputStream.readObject();
			try(BufferedWriter writer = Files.newBufferedWriter(Paths.get(hpdsDirectory + "columnMeta.csv"), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
				CSVPrinter printer = new CSVPrinter(writer, CSVFormat.DEFAULT);
				for(String key : metastore.keySet()) {
					ColumnMeta columnMeta = metastore.get(key);
					Object[] columnMetaOut = new Object[11];

					StringBuilder listQuoted = new StringBuilder();
					AtomicInteger x = new AtomicInteger(1);

					if(columnMeta.getCategoryValues() != null){
						if(!columnMeta.getCategoryValues().isEmpty()) {
							columnMeta.getCategoryValues().forEach(string -> {
								listQuoted.append(string);
								if(x.get() != columnMeta.getCategoryValues().size()) listQuoted.append("µ");
								x.incrementAndGet();
							});
						}
					}

					columnMetaOut[0] = columnMeta.getName();
					columnMetaOut[1] = String.valueOf(columnMeta.getWidthInBytes());
					columnMetaOut[2] = String.valueOf(columnMeta.getColumnOffset());
					columnMetaOut[3] = String.valueOf(columnMeta.isCategorical());
					// this should nest the list of values in a list inside the String array.
					columnMetaOut[4] = listQuoted;
					columnMetaOut[5] = String.valueOf(columnMeta.getMin());
					columnMetaOut[6] = String.valueOf(columnMeta.getMax());
					columnMetaOut[7] = String.valueOf(columnMeta.getAllObservationsOffset());
					columnMetaOut[8] = String.valueOf(columnMeta.getAllObservationsLength());
					columnMetaOut[9] = String.valueOf(columnMeta.getObservationCount());
					columnMetaOut[10] = String.valueOf(columnMeta.getPatientCount());

					printer.printRecord(columnMetaOut);
				}

				writer.flush();
            }

		} catch (IOException | ClassNotFoundException e) {
			throw new RuntimeException("Could not load metastore", e);
		}
	}

}
