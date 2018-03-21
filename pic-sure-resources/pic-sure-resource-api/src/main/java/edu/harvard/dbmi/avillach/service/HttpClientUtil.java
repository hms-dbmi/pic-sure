package edu.harvard.dbmi.avillach.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.harvard.dbmi.avillach.exception.ResourceCommunicationException;
import org.apache.commons.io.IOUtils;
import org.apache.http.Header;
import org.apache.http.HttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.client.HttpClient;
import org.apache.log4j.Logger;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class HttpClientUtil {
	private final static ObjectMapper json = new ObjectMapper();
    private static Logger logger = Logger.getLogger(HttpClientUtil.class);
    private final static HttpClient client = HttpClientBuilder.create().build();


    public static HttpResponse retrieveGetResponse(String uri, Header[] headers) {
		try {
            logger.debug("HttpClientUtil retrieveGetResponse()");
			HttpGet get = new HttpGet(uri);
			get.setHeaders(headers);
			return client.execute(get);
		} catch (IOException e) {
			//TODO: Write custom exception
			throw new ResourceCommunicationException(uri, e);
		}
	}

	public static HttpResponse retrievePostResponse(String uri, Header[] headers, String body) {
		try {
		    logger.debug("HttpClientUtil retrievePostResponse()");
			HttpPost post = new HttpPost(uri);
            post.setEntity(new StringEntity(body));
            post.setHeaders(headers);
			post.addHeader("Content-type","application/json");
			return client.execute(post);
		} catch (IOException e) {
			//TODO: Write custom exception
			throw new ResourceCommunicationException(uri, e);
		}
	}

	public static <T> List<T> readListFromResponse(HttpResponse response, Class<T> expectedElementType) {
        logger.debug("HttpClientUtil readListFromResponse()");

        try {
			String responseBody = IOUtils.toString(response.getEntity().getContent(), "UTF-8");
			return json.readValue(responseBody, new TypeReference<List<T>>() {});
		} catch (IOException e) {
        	//TODO: Write custom exception
			throw new RuntimeException("Incorrect object type returned");
		}
	}

    public static <T> T readObjectFromResponse(HttpResponse response, Class<T> expectedElementType) {
        logger.debug("HttpClientUtil readObjectFromResponse()");
        try {
            String responseBody = IOUtils.toString(response.getEntity().getContent(), "UTF-8");
            return json.readValue(responseBody, json.getTypeFactory().constructType(expectedElementType));
        } catch (IOException e) {
			//TODO: Write custom exception
			throw new RuntimeException("Incorrect object type returned");

		}
    }
}
