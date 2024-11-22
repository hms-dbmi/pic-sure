package edu.harvard.hms.dbmi.avillach;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.harvard.dbmi.avillach.util.exception.ResourceCommunicationException;
import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.util.EntityUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class HttpClientUtil {
	private final static ObjectMapper json = new ObjectMapper();

	private static final Logger logger = LoggerFactory.getLogger(HttpClientUtil.class);

	public static HttpResponse retrieveGetResponse(String uri, String token) {
		try {
			HttpClient client = HttpClientBuilder.create().build();
			HttpGet get = new HttpGet(uri);
			if (token != null) {
                get.addHeader("AUTHORIZATION", "Bearer " + token);
            }
			return client.execute(get);
		} catch (IOException e) {
			throw new ResourceCommunicationException(uri, e);
		}
	}

	public static <T> List<T> readDataObjectsFromResponse(HttpResponse response, Class<T> expectedElementType) {
		try {
			String responseBody = EntityUtils.toString(response.getEntity(), "UTF-8");
			// Get only the data_objects field from the returned structure. Ugly, but has to de- and then re-serialize
            JsonNode jn = json.readTree(responseBody);
            if (null == jn.get("data_objects")) {
                Object singleObject = json.readValue(jn.get("data_object").toString(), new TypeReference<Object>() {});
                ArrayList<T> sl = new ArrayList<T>();
                sl.add((T) singleObject);
                return sl;
            } else {
                return json.readValue(jn.get("data_objects").toString(), new TypeReference<List<T>>() {});
            }
		} catch (IOException e) {
			logger.error("Error reading object from response, returning empty list", e);
			return new ArrayList<T>();
		}
	}

}
