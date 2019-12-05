package edu.harvard.hms.dbmi.avillach.auth.utils;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.type.MapType;
import edu.harvard.dbmi.avillach.util.exception.ApplicationException;
import edu.harvard.hms.dbmi.avillach.auth.JAXRSConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.*;
import javax.validation.constraints.NotNull;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
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
 *     will appear here: string, list, map.
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
	 *     When a JSONArray element merges with another JSONArray element, only the data in the same corresponding
	 *     location will be merged, and the rest of elements in the longer array will be simply attached.
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

	/**
	 * merge two JSON into a new list. Example use case: merge two string together
	 *
	 * @param a
	 * @param b
	 * @return a new list that contains two input JSONs
	 */
	public static List mergeToNewList(Object a, Object b){
		List list = new ArrayList();
		list.add(a);
		list.add(b);
		return list;
	}

	/**
	 * merge a string into a list.
	 * <p>
	 *     Notice: the list could include JSON string, array, or map.
	 * </p>
	 * The logic here is, before merging, it will check if the list already contains the same string, if not,
	 * will merge.
	 *
	 * @param s
	 * @param list
	 * @return
	 */
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
	 * And if two same-location elements are not in the same type, only the element in baseList will be kept
	 *
	 * @param baseList
	 * @param incomingList
	 * @return
	 */
	public static List mergeListToList(List baseList, List incomingList){    	
		List mergedList = new ArrayList();
		
		// incomingList should never be null
		addElementsOfListToMergedList(mergedList, baseList);
		if (incomingList.size()==0) {
			return baseList;
		}else if(incomingList.get(0) instanceof String) {
			addElementsOfListToMergedList(mergedList, incomingList);
			addElementsOfListToMergedList(mergedList, baseList);        		
		} else {
			for (int i = 0 ; i < baseList.size() ; i++) {
				Object baseElement = baseList.get(i);
				Object incomingElement = incomingList.get(i);
				if (baseElement.getClass() == incomingElement.getClass() ) {
					if (baseElement instanceof List){
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
		}

		return mergedList;
	}

	private static void addElementsOfListToMergedList(List mergedList, List baseList) {
		if(baseList!=null&&baseList.size()>0) {
			mergedList.addAll(baseList);
		}
	}

	/**
	 *  attach or merge a map to a list, the list might contain many elements that are string, list or map that are converted from JSONs.
	 *  The logic here is first check if there is any map element that has the same structure based on the method <code>isMapMergeable<code/>,
	 *  if true, merge, if false, simply attach.
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
		throw new ApplicationException("Inner application error, please contact admin.");
	}

	/*
	 * Get the JSON string from a URL
	 */
	public static Map<String, String> getFENCEMapping() throws JsonProcessingException, KeyManagementException, NoSuchAlgorithmException {
		// Create FENCE group mapping
		// String fenceMapping = Files.readString(Paths.get("/tmp/fence_mapping.json"));

		/*
		 *  fix for
		 *    Exception in thread "main" javax.net.ssl.SSLHandshakeException:
		 *       sun.security.validator.ValidatorException:
		 *           PKIX path building failed: sun.security.provider.certpath.SunCertPathBuilderException:
		 *               unable to find valid certification path to requested target
		 */
		TrustManager[] trustAllCerts = new TrustManager[] {
				new X509TrustManager() {
					public java.security.cert.X509Certificate[] getAcceptedIssuers() {
						return null;
					}

					public void checkClientTrusted(java.security.cert.X509Certificate[] certs, String authType) {  }

					public void checkServerTrusted(java.security.cert.X509Certificate[] certs, String authType) {  }

				}
		};

		SSLContext sc = SSLContext.getInstance("SSL");
		sc.init(null, trustAllCerts, new java.security.SecureRandom());
		HttpsURLConnection.setDefaultSSLSocketFactory(sc.getSocketFactory());

		// Create all-trusting host name verifier
		HostnameVerifier allHostsValid = new HostnameVerifier() {
			public boolean verify(String hostname, SSLSession session) {
				return true;
			}
		};
		// Install the all-trusting host verifier
		HttpsURLConnection.setDefaultHostnameVerifier(allHostsValid);
		/*
		 * end of the fix
		 */

		String fence_mapping_json_string = null;
		URL u = null;
		try {
			u = new URL(JAXRSConfiguration.fence_mapping_url);
		} catch (MalformedURLException e) {
			e.printStackTrace();
		}
		try (InputStream in = u.openStream()) {
			fence_mapping_json_string =  new String(in.readAllBytes(), StandardCharsets.UTF_8);
		} catch (IOException e) {
			e.printStackTrace();
			logger.error("checkIDPProvider() Could not read data "+e.getMessage());
		}
		logger.debug("checkIDPProvider() Got JSON from FENCE_MAPPING_URL");

		//ObjectMapper mapper = objectMapper; //new ObjectMapper();
		MapType type = JAXRSConfiguration.objectMapper.getTypeFactory().constructMapType(Map.class, String.class, String.class);
		Map<String, String> fence_mapping = JAXRSConfiguration.objectMapper.readValue(fence_mapping_json_string, type);
		for (String projectName : fence_mapping.keySet()) {
			logger.debug("checkIDPProvider() projectName mapping "+projectName);
		}
		logger.debug("checkIDPProvider() Mapped fence project to concept path.");

		return fence_mapping;
	}

}
