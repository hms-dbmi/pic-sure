package edu.harvard.hms.dbmi.avillach.hpds.storage;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map.Entry;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;

import com.google.common.collect.Lists;
import com.google.common.collect.Range;

public class FileBackedIndex implements Serializable {

	/**
	 * 
	 */
	private static final long serialVersionUID = 5089713203903957829L;
	public Double min = Double.MAX_VALUE, max = Double.MIN_VALUE;
	private FileBackedByteIndexedStorage<Range, TreeMap> rangeMap;
	private int valueCount;

	public TreeMap<Double, TreeSet<String>> buildContinuousValuesMap(FileBackedByteIndexedStorage<String, String[]> allValues) {
		TreeMap<Double, TreeSet<String>> continuousValueMap = new TreeMap<>();
		for(String key : allValues.keys()) {
			try{
				Double DoubleValue = Double.parseDouble(key.trim());
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
							Double DoubleValue = Double.parseDouble(value.trim());
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

	private void setMinAndMax(Double DoubleValue) {
		if(min > DoubleValue) {
			min = DoubleValue;
		}
		if(max < DoubleValue) {
			max = DoubleValue;
		}
	}

	public void buildIndex(TreeMap<Double, TreeSet<String>> continuousValueMap, File storageFile) throws FileNotFoundException {
		this.rangeMap = new FileBackedByteIndexedStorage<Range, TreeMap>(Range.class, TreeMap.class, storageFile);
		Set<Double> continuousValuesMapKeys = new TreeSet<Double>(continuousValueMap.keySet());
		List<List<Double>> partitions = Lists.partition(new ArrayList<Double>(continuousValuesMapKeys), 1000);
		HashMap<Range<Double>, TreeMap<Double, TreeSet<String>>> rangeMap = new HashMap<>();
		List<Double> partition = partitions.get(0);
		SortedMap<Double, TreeSet<String>> partitionMap = continuousValueMap.subMap(partition.get(0), partition.get(partition.size()-1));
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
		rangeMap.entrySet().stream().forEach((Entry<Range<Double>, TreeMap<Double, TreeSet<String>>> entry)-> {
			try {
				this.rangeMap.put(entry.getKey(), entry.getValue());
			} catch (IOException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		});
		this.rangeMap.complete();
	}

	public List<String> getValuesInRange(Range<Double> targetRange) {

		System.out.println("Getting valuesInRange : " + targetRange);
		// Get a list of ranges that are connected to the target range
		List<Range> connectedRanges = rangeMap.keys().stream().filter((range)->{
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
					TreeMap<Double, TreeSet<String>> continousValueMap = retrieveRangeMap(range);
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
		try {
			return rangeMap.get(range);
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
			return null;
		}
	}

	public int getValueCount() {
		return valueCount;
	}

	public void setValueCount(int valueCount) {
		this.valueCount = valueCount;
	}

}
