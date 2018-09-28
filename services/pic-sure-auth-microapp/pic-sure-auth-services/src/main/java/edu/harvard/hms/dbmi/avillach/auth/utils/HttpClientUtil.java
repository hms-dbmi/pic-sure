package edu.harvard.hms.dbmi.avillach.auth.utils;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.harvard.dbmi.avillach.util.exception.ApplicationException;
import edu.harvard.hms.dbmi.avillach.auth.JAXRSConfiguration;
import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.HttpClientBuilder;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

public class HttpClientUtil {

	public static HttpResponse retrieveGetResponse(String uri, String token) {
		try {
			HttpClient client = HttpClientBuilder.create().build();
			HttpGet get = new HttpGet(uri);
			if (token != null) {
                get.addHeader("AUTHORIZATION", "Bearer " + token);
            }
			return client.execute(get);
		} catch (IOException e) {
			throw new ApplicationException(uri, e);
		}
	}

	public static HttpResponse retrievePostResponse(String uri, String token) {
		try {
			HttpClient client = HttpClientBuilder.create().build();
			HttpPost post = new HttpPost(uri);
            if (token != null) {
                Map<String, String> clientCredentials = new HashMap<String, String>();
                clientCredentials.put("BEARER_TOKEN", token);
                post.setEntity(new StringEntity(JAXRSConfiguration.objectMapper.writeValueAsString(clientCredentials)));
            }
			post.setHeader("Content-type","application/json");
			return client.execute(post);
		} catch (IOException e) {
			throw new ApplicationException(uri, e);
		}
	}

}
