package edu.harvard.hms.dbmi.avillach.hpds.data.genotype;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import org.apache.commons.io.output.ByteArrayOutputStream;

import com.google.common.collect.Lists;
import com.google.common.collect.Range;

import edu.harvard.hms.dbmi.avillach.hpds.storage.FileBackedByteIndexedStorage;

public class CompressedIndex implements Serializable {

	/**
	 * 
	 */
	private static final long serialVersionUID = 5089713203903957829L;
	public Float min = Float.MAX_VALUE, max = Float.MIN_VALUE;
	private HashMap<Range<Float>, byte[]> compressedRangeMap;
	private int valueCount;

	public TreeMap<Float, TreeSet<String>> buildContinuousValuesMap(FileBackedByteIndexedStorage<String, String[]> allValues) {
		TreeMap<Float, TreeSet<String>> continuousValueMap = new TreeMap<>();
		for(String key : allValues.keys()) {
			try{
				Float DoubleValue = Float.parseFloat(key.trim());
				TreeSet<String> currentValues = continuousValueMap.get(DoubleValue);
				if(currentValues == null) {
					currentValues = new TreeSet<>();
					continuousValueMap.put(DoubleValue, currentValues);
				}
				currentValues.add(key);
				continuousValueMap.put(DoubleValue, currentValues);
				setMinAndMax(DoubleValue);
			}catch(NumberFormatException e) {
				String[] valuesInKey = key.split(",");
				if(valuesInKey.length > 1) {
					for(String value : valuesInKey) {
						try {
							Float DoubleValue = Float.parseFloat(value.trim());
							TreeSet<String> currentValues = continuousValueMap.get(DoubleValue);
							if(currentValues == null) {
								currentValues = new TreeSet<>();
								continuousValueMap.put(DoubleValue, currentValues);
							}
							currentValues.add(key);
							continuousValueMap.put(DoubleValue, currentValues);
							setMinAndMax(DoubleValue);
						}catch(NumberFormatException e3) {
							System.out.println("Unable to parse value : " + value.trim());
						}
					}
				}
			}
		}
		this.setValueCount(continuousValueMap.size());
		return continuousValueMap;
	}

	private void setMinAndMax(Float DoubleValue) {
		if(min > DoubleValue) {
			min = DoubleValue;
		}
		if(max < DoubleValue) {
			max = DoubleValue;
		}
	}

	public void buildIndex(TreeMap<Float, TreeSet<String>> continuousValueMap) {
		Set<Float> continuousValuesMapKeys = new TreeSet<Float>(continuousValueMap.keySet());
		List<List<Float>> partitions = Lists.partition(new ArrayList<Float>(continuousValuesMapKeys), 1000);
		HashMap<Range<Float>, TreeMap<Float, TreeSet<String>>> rangeMap = new HashMap<>();
		List<Float> partition = partitions.get(0);
		SortedMap<Float, TreeSet<String>> partitionMap = continuousValueMap.subMap(partition.get(0), partition.get(partition.size()-1));
		rangeMap.put(
				Range.openClosed(partition.get(0), partition.get(partition.size()-1)), 
				new TreeMap<>(partitionMap));
		for(int x = 0; x < partitions.size()-1; x++) {
			partition = partitions.get(x);
			partitionMap = continuousValueMap.subMap(partition.get(0), partition.get(partition.size()-1));
			rangeMap.put(Range.openClosed(partition.get(0), partitions.get(x+1).get(0)), new TreeMap<>(partitionMap));
		}
		partition = partitions.get(partitions.size()-1);
		partitionMap = continuousValueMap.subMap(partition.get(0), partition.get(partition.size()-1));
		rangeMap.put(Range.openClosed(partition.get(0), partition.get(partition.size()-1)), new TreeMap<>(partitionMap));
		compressedRangeMap = new HashMap<>(rangeMap.entrySet().stream().collect(
				Collectors.toMap(
						(key)->{return key.getKey();}, 
						(part)->{
							byte[] compressed = null;
							try(
									ByteArrayOutputStream baos = new ByteArrayOutputStream();
									GZIPOutputStream gzos = new GZIPOutputStream(baos);
									ObjectOutputStream out = new ObjectOutputStream(gzos);
									){
								out.writeObject(part.getValue());
								out.flush();
								out.close();
								gzos.flush();
								gzos.close();
								compressed = baos.toByteArray();
							} catch (IOException e) {
								// TODO Auto-generated catch block
								e.printStackTrace();
							}
							return compressed;
						})
				));
	}

	public List<String> getValuesInRange(Range<Float> targetRange) {

		System.out.println("Getting valuesInRange : " + targetRange);
		// Get a list of ranges that are connected to the target range
		List<Range> connectedRanges = compressedRangeMap.keySet().stream().filter((range)->{
			return range.isConnected(targetRange);
		}).collect(Collectors.toList());

		// Get a list of ranges that enclose the target range completely
		List<Range> enclosingRanges = connectedRanges.stream().filter((range)->{
			return targetRange.encloses(range);
		}).collect(Collectors.toList());

		List<String> valuesInRange = new ArrayList<>();

		// Add all variants from enclosing ranges
		enclosingRanges.forEach(
				range->{
					TreeMap<Double, TreeSet<String>> continousValueMap = retrieveRangeMap(range);
					continousValueMap.values().forEach(
							variantSet->{
								System.out.println("Adding : " + variantSet.first() + " : " + variantSet.last());
								valuesInRange.addAll(variantSet);
							});
				});

		// We already added all variants in the enclosing ranges
		connectedRanges.removeAll(enclosingRanges);

		connectedRanges.forEach(
				range ->{
					TreeMap<Float, TreeSet<String>> continousValueMap = retrieveRangeMap(range);
					System.out.println("Searching within : " + range.lowerEndpoint() + " : " + range.upperEndpoint());
					continousValueMap.entrySet().stream().forEach(
							entry->{
								if(targetRange.contains(entry.getKey())) {
									System.out.println("Adding : " + entry.getValue().first() + " : " + entry.getValue().last());
									valuesInRange.addAll(entry.getValue());
								}
							});
				});
		return valuesInRange;
	}

	private TreeMap<Double, TreeSet<String>> retrieveRangeMap(Range<Double> range) {
		TreeMap<Double, TreeSet<String>> continousValueMap = null;
		try(
				ByteArrayInputStream bais = new ByteArrayInputStream(compressedRangeMap.get(range));
				GZIPInputStream gzis = new GZIPInputStream(bais);
				ObjectInputStream ois = new ObjectInputStream(gzis);
				){
			continousValueMap = (TreeMap<Double, TreeSet<String>>)ois.readObject();
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (ClassNotFoundException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		return continousValueMap;
	}

	public int getValueCount() {
		return valueCount;
	}

	public void setValueCount(int valueCount) {
		this.valueCount = valueCount;
	}

}
