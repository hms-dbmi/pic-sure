package edu.harvard.hms.dbmi.avillach.auth.utils;

import edu.harvard.dbmi.avillach.util.exception.ApplicationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.validation.constraints.NotNull;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Originally the class is designed for merging two JSON maps (input as two Map), there
 * is only mergeTemplateMap class is public. But afterwards, the methods inside which are private
 * at the beginning, seems could be used as utility methods as well, so they became public.
 * <b>However,</b> there is one thing that needs to be noticed when using these methods that these
 * methods are designed specifically for query template merging use cases, which means might not fit into
 * other use cases. So be careful!!
 */
public class JsonUtils {

    static Logger logger = LoggerFactory.getLogger(JsonUtils.class);

    /**
     * The logic here are:
     * <li>
     *     Only takes JSON map as input - this could be extended as take any JSON format as input
     * </li>
     * <li>
     *     When a JSON map merge with another map, it will recursively merge every level of JSON Objects.
     * </li>
     * <li>
     *     Data in the same level will be merged, which means data of level 3 in JSON map A will not be merged as level
     *     2 in JSON map B.
     * </li>
     * <li>
     *     When a JSONArray element merges with another JSONArray element, only the data in the same corresponding
     *     location will be merged, and the rest of elements in the longer array will be attached to the shorter one.
     * </li>
     * <li>
     *     When a JSON Map merge into an Json Array, it will be either append or merge into one of the element that is a
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

                if (value instanceof String) {
                    if (originValue instanceof String) {
                        if (value.equals(originValue))
                            continue;
                        else {
                            originMap.put(key,mergeToNewList(originValue, value));
                        }
                    } else if (originValue instanceof Map){
                        originMap.put(key,mergeToNewList(originValue, value));
                    } else if (originValue instanceof List) {
                        originMap.put(key, mergeStringToList((String)value, (List)originValue));
                    } else {
                        logJsonTypeException(originValue);
                    }
                } else if (value instanceof List) {
                    if (originValue instanceof String) {
                        originMap.put(key, mergeStringToList((String)originValue, (List)value));
                    } else if (originValue instanceof List) {
                        originMap.put(key, mergeListToList((List)originValue, (List)value));
                    } else if (originValue instanceof Map) {
                        mergeMapToList((Map)originValue, (List)value);
                    } else {
                        logJsonTypeException(originValue);
                    }
                } else if (value instanceof Map) {
                    if (originValue instanceof Map){
                        originMap.put(key, mergeTemplateMap((Map)originValue, (Map)value));
                    } else if (originValue instanceof String){
                        originMap.put(key, mergeToNewList(originValue, value));
                    } else if (originValue instanceof List) {
                        mergeMapToList((Map)value, (List)originValue);
                    } else {
                        logJsonTypeException(originValue);
                    }
                } else {
                    logJsonTypeException(value);
                }
            } else {
                originMap.put(key, value);
            }
        }

        return mergedMap;
    }

    public static List mergeToNewList(Object a, Object b){
        List list = new ArrayList();
        list.add(a);
        list.add(b);
        return list;
    }

    public static List mergeStringToList(String s, List list){
        boolean isExist = false;
        for (Object object: list){
            if (object instanceof String && s.equals(object)){
                isExist = true;
                break;
            }
        }
        if (!isExist){
            list.add(s);
        }

        return list;
    }

    /**
     * will merge the two elements in the same location.
     * meaning baseList(0) will merge with incomingList(0) ...
     * So if two same-location elements are not in the same type, will only keep the element in baseList
     *
     * @param baseList
     * @param incomingList
     * @return
     */
    public static List mergeListToList(List baseList, List incomingList){
        List mergedList = new ArrayList();
        for (int i = 0 ; i < baseList.size() ; i++) {
            Object baseElement = baseList.get(i);
            Object incomingElement = incomingList.get(i);
            if (baseElement.getClass() == incomingElement.getClass() ) {
                if (baseElement instanceof String) {
                    mergedList.add(baseElement);
                    mergedList.add(incomingElement);
                } else if (baseElement instanceof List){
                    mergedList.add(mergeListToList((List)baseElement, (List)incomingElement));
                } else if (baseElement instanceof Map){
                    mergedList.add(mergeTemplateMap((Map)baseElement, (Map)incomingElement));
                } else {
                    logJsonTypeException(baseElement);
                }
            } else {
                mergedList.add(baseElement);
            }
        }

        return mergedList;
    }

    /**
     *  attach or merge a map to a list, the list might contain many elements that are JSON Object,
     *  JSON Array, JSON map, or a JSON string. To attach or to merge, depends on the method <code>isMapMergeable<code/>
     *
     * @param map
     * @param list
     * @return
     */
    public static List mergeMapToList(Map map, List list){
        List mergedList = new ArrayList(list.size());

        boolean merged = false;
        for (int i = 0; i<list.size(); i++){
            Object element = list.get(i);
            if (element instanceof Map && isMapMergeable((Map)element, map)){
                mergedList.addAll(list.subList(0,i));
                mergedList.add(mergeTemplateMap((Map)element, map));
                mergedList.addAll(list.subList(i+1, list.size()));
                merged = true;
            }
        }

        if (merged) {
            return mergedList;
        } else {
            list.add(map);
            return list;
        }
    }

    /**
     * only use for checking JSON, so the map keys will always be String
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
        throw new ApplicationException("Inner application error, please contact admin.");
    }


}
