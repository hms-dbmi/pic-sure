package edu.harvard.hms.dbmi.avillach;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.io.IOUtils;
import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpEntityEnclosingRequestBase;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.HttpClientBuilder;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import edu.harvard.dbmi.avillach.exception.ResourceCommunicationException;

public class HttpClientUtil {
	private final static ObjectMapper json = new ObjectMapper();

	public static HttpResponse retrieveGetResponse(String uri, String token) {
		try {
			HttpClient client = HttpClientBuilder.create().build();
			HttpGet get = new HttpGet(uri);
			get.addHeader("AUTHORIZATION", "Bearer " + token);
			return client.execute(get);
		} catch (IOException e) {
			throw new ResourceCommunicationException(uri, e);
		}
	}

	public static HttpResponse retrievePostResponse(String uri, String token) {
		try {
			HttpClient client = HttpClientBuilder.create().build();
			HttpPost post = new HttpPost(uri);
			Map<String, String> clientCredentials = new HashMap<String, String>();
			clientCredentials.put("IRCT_BEARER_TOKEN", "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiJ0ZXN0fGF2bGJvdEBkYm1pLmhtcy5oYXJ2YXJkLmVkdSIsImVtYWlsIjoiYXZsYm90QGRibWkuaG1zLmhhcnZhcmQuZWR1In0.51TYsm-uw2VtI8aGawdggbGdCSrPJvjtvzafd2Ii9NU");
			post.setHeader("Content-type","application/json");
			post.setEntity(new StringEntity(json.writeValueAsString(clientCredentials)));
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

}
