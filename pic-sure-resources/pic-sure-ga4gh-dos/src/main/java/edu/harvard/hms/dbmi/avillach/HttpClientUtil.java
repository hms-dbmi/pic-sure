package edu.harvard.hms.dbmi.avillach;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import org.apache.commons.io.IOUtils;
import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.HttpClientBuilder;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import edu.harvard.dbmi.avillach.util.exception.ResourceCommunicationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class HttpClientUtil {
	private final static ObjectMapper json = new ObjectMapper();

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

	public static HttpResponse retrievePostResponse(String uri, String token) {
		try {
			HttpClient client = HttpClientBuilder.create().build();
			HttpPost post = new HttpPost(uri);
            if (token != null) {
                Map<String, String> clientCredentials = new HashMap<String, String>();
                clientCredentials.put("BEARER_TOKEN", token);
                post.setEntity(new StringEntity(json.writeValueAsString(clientCredentials)));
            }
			post.setHeader("Content-type","application/json");
			return client.execute(post);
		} catch (IOException e) {
			throw new ResourceCommunicationException(uri, e);
		}
	}

	public static <T> List<T> readListFromResponse(HttpResponse response, Class<T> expectedElementType) {
		try {
			String responseBody = IOUtils.toString(response.getEntity().getContent(), "UTF-8");
			return json.readValue(responseBody, new TypeReference<List<T>>() {});
		} catch (IOException e) {
			e.printStackTrace();
			return new ArrayList<T>();
		}
	}

	public static <T> List<T> readDataObjectsFromResponse(HttpResponse response, Class<T> expectedElementType) {
		try {
			String responseBody = IOUtils.toString(response.getEntity().getContent(), "UTF-8");
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
			e.printStackTrace();
			return new ArrayList<T>();
		}
	}

}
