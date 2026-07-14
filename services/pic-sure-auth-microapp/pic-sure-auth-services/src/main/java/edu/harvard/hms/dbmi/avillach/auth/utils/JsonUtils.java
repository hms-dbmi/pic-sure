package edu.harvard.hms.dbmi.avillach.auth.utils;

import jakarta.validation.constraints.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.stream.Collectors;

/**
 * <p>
 *     The scope of this class is only for operations on string, list, map that are converted from JSONs. All
 *     map keys we deal with will only be String. There are only three types of data: string, list, map,
 *     since input maps are converted from JSONs.
 * </p>
 * <p> * Originally this class was designed for merging two JSON maps (inputs are two Maps),
 * only the mergeTemplateMap class is public. Now, the private methods could be used as utility methods as well,
 * so they were made public.
 * However, these methods are not designed for general usage, but specifically for query template merging use cases.
 * Use caution when using these methods for other use cases.</p>
 * <p>
 *     Notice: the input Map or list are from the conversion of JSONs, which means only three possible formats
 *     will appear here: string, list, map. These classes are changed internally to allow some field consolidation,
 *     so it is safer to use 'Collection' instead of 'List' when referencing the returned map.
 * </p>
 */
public class JsonUtils {

	static Logger logger = LoggerFactory.getLogger(JsonUtils.class);

	/**
	 * The logic for this method here is:
	 * <li>
	 *     Only takes JSON map as input - this could be extended as take any JSON format as input in the future
	 * </li>
	 * <li>
	 *     When a JSON map merge with another map, it will recursively merge every level of JSON Objects.
	 * </li>
	 * <li>
	 *     Data in the same level will be merged, which means data of level 3 in JSON map A will not be merged as level
	 *     2 in JSON map B.
	 * </li>
	 * <li>
	 *     When a JSONArray element merges with another JSONArray element, a single copy of each string element is
	 *     preserved.  Each instance of other objects (e.g., Maps) are present in the resulting Collection.  This means
	 *     that merging multiple VariantInfoFilters is not currently supported.
	 * </li>
	 * <li>
	 *     When a JSON Map merge into a Json Array, it will be either append or merge into one of the element that is a
	 *     Json Map as well and has the same structure based on isMapMergeable method
	 * </li>
	 * @param originMap
	 * @param incomingMap
	 * @return
	 */
	public static Map<String, Object> mergeTemplateMap(@NotNull Map<String, Object> originMap,
			@NotNull Map<String, Object> incomingMap){
		
		Map mergedMap = originMap;

		for (Map.Entry<String, Object> entry : incomingMap.entrySet()){
			String key = entry.getKey();
			Object value = entry.getValue();

			if (originMap.containsKey(key)){
				Object originValue = originMap.get(key);
				
				//first check for valid types.  this will throw an exception if an unhandled type is used
				if(! ( value instanceof String       || value instanceof Map       || value instanceof Collection)  ||
				   ! ( originValue instanceof String || originValue instanceof Map || originValue instanceof Collection)) {
					logJsonTypeException(originValue);
				}

				if (value instanceof String) {
					
					if (originValue instanceof String || originValue instanceof Map) {
						originMap.put(key,mergeToNewSet(originValue, value));
					} else if (originValue instanceof Set) { //we are only adding sets to our map
						((Set)originValue).add(value);
					}
				} else if (value instanceof Collection) {  //we don't know what the input will be, so check super type
					if (originValue instanceof String || originValue instanceof Collection) {
						originMap.put(key, mergeToNewSet(originValue, value));
					} else if (originValue instanceof Map) {
						mergeMapToSet((Map)originValue, (Collection)value);
					}
				} else if (value instanceof Map) {
					if (originValue instanceof Map){
						originMap.put(key, mergeTemplateMap((Map)originValue, (Map)value));
					} else if (originValue instanceof String){
						originMap.put(key, mergeToNewSet(originValue, value));
					} else if (originValue instanceof Collection) {
						mergeMapToSet((Map)value, (Collection)originValue);
					}
				}
			} else {
				originMap.put(key, value);
			}
		}

		return mergedMap;
	}

	/**
	 * merge two JSON into a new list. Example use case: merge two string together.  if the argument
	 * is a collection, each element will be added to the returned Set
	 *
	 * @param a
	 * @param b
	 * @return a new list that contains two input JSONs
	 */
	public static Set mergeToNewSet(Object a, Object b){
		Set newSet = new HashSet();
		
		if(a instanceof Collection) {
			newSet.addAll((Collection)a);
		} else {
			newSet.add(a);
		}
		
		if(b instanceof Collection) {
			newSet.addAll((Collection)b);
		} else {
			newSet.add(b);
		}
		
		return newSet;
	}

	
	/**
	 *  attach or merge a map to a list, the list might contain many elements that are string, list or map that are converted from JSONs.
	 *  The logic here is first check if there is any map element that has the same structure based on the method <code>isMapMergeable<code/>,
	 *  if true, merge, if false, simply attach.
	 *
	 * @param map a map that is converted from a JSON.
	 * @param collection a list that contains many elements that are string, list or map that are converted from JSONs.
	 * @return a new list that contains the merged map.
	 */
	public static Set mergeMapToSet(Map map, Collection collection){
		Set mergedSet = new HashSet(collection.size());

		boolean merged = false;
		for (Object element : collection) {
			//only merge the map once if it matches another map.
			if (!merged && element instanceof Map && isMapMergeable((Map)element, map)){
				mergedSet.add(mergeTemplateMap((Map)element, map));
				merged = true;
			} else {
				mergedSet.add(element);
			}
		}

		//if we didn't find a mergeable map, just add the given map to the Set.
		if(!merged) {
			mergedSet.add(map);
		}
		return mergedSet;
	}

	/**
	 * only use for checking if two maps have the same keys. All keys will only be string. Otherwise, the map is not
	 * converted from a JSON, which is not in the scope of this util class.
	 * @param baseMap
	 * @param incomingMap
	 * @return
	 */
	public static boolean isMapMergeable(Map baseMap, Map incomingMap){
		Set<String> baseMapKeys = new TreeSet<>();
		for (Object key : baseMap.keySet()){
			baseMapKeys.add((String)key);
		}

		Set<String> incomingMapKeys = new TreeSet<>();
		for (Object key : incomingMap.keySet()){
			incomingMapKeys.add((String)key);
		}

		return baseMapKeys.stream().collect(Collectors.joining())
				.equals(incomingMapKeys.stream().collect(Collectors.joining()));
	}

	private static void logJsonTypeException(Object value){
		logger.error("Incoming JSON Object is a type: " + value.getClass() + ", can only merge String, List and Map!");
		throw new IllegalArgumentException("Inner application error, please contact admin.");
	}

}
