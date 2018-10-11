package edu.harvard.hms.dbmi.avillach.auth.utils;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.harvard.dbmi.avillach.util.exception.ApplicationException;
import edu.harvard.hms.dbmi.avillach.auth.JAXRSConfiguration;
import org.apache.http.Header;
import org.apache.http.HttpHeaders;
import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.HttpClientBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.util.HashMap;
import java.util.Map;

import javax.ws.rs.core.Response;

import static java.util.Map.of;

public class HttpClientUtil {

	private static Logger logger = LoggerFactory.getLogger(HttpClientUtil.class);

	public static HttpResponse retrieveGetResponse(String uri, String token) {
		try {
			HttpClient client = HttpClientBuilder.create().build();
			HttpGet get = new HttpGet(uri);
			if (token != null) {
                get.addHeader("AUTHORIZATION", "Bearer " + token);
            }
			return client.execute(get);
		} catch (IOException e) {
			throw new ApplicationException(Response.Status.fromStatusCode(500), e);
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
			throw new ApplicationException(Response.Status.fromStatusCode(500), e);
		}
	}

	public static InputStream simplePost(String uri, StringEntity requestBody, HttpClient client, Header... headers){

		HttpPost post = new HttpPost(uri);
		post.setHeaders(headers);
		post.setEntity(requestBody);

		HttpResponse response;

		try {
			response = client.execute(post);
		} catch (IOException ex){
			logger.error("simplePost() Exception: " + ex.getMessage() +
					", cannot get response by POST from url: " + uri);
			throw new ApplicationException("Inner problem, please contact system admin and check the server log");
		}

		try {
			return response.getEntity().getContent();
		} catch (IOException ex){
			logger.error("simplePost() cannot get content by POST from url: " + uri);
			throw new ApplicationException("Inner problem, please contact system admin and check the server log");
		}
	}

	public static JsonNode simplePost(String uri, StringEntity requestBody, HttpClient client, ObjectMapper objectMapper, Header... headers){
		try {
			return objectMapper.readTree(simplePost(uri, requestBody, client, headers));
		} catch (IOException ex){
			logger.error("simplePost() Exception: " + ex.getMessage()
					+ ", cannot parse content from by POST from url: " + uri);
			throw new ApplicationException("Inner problem, please contact system admin and check the server log");
		}
	}

	public static InputStream simpleGet(String uri, HttpClient client, Header... headers){
		HttpGet get = new HttpGet(uri);
		get.setHeaders(headers);

		HttpResponse response;

		try {
			response = client.execute(get);
		} catch (IOException ex){
			logger.error("simpleGet() cannot get response by GET from url: " + uri);
			throw new ApplicationException("Inner problem, please contact system admin and check the server log");
		}

		try {
			return response.getEntity().getContent();
		} catch (IOException ex){
			logger.error("simpleGet() cannot get content by GET from url: " + uri);
			throw new ApplicationException("Inner problem, please contact system admin and check the server log");
		}
	}

	public static JsonNode simpleGet(String uri, HttpClient client, ObjectMapper objectMapper, Header... headers){
		try {
			return objectMapper.readTree(simpleGet(uri, client, headers));
		} catch (IOException ex){
			logger.error("simpleGet() cannot parse content from by GET from url: " + uri);
			throw new ApplicationException("Inner problem, please contact system admin and check the server log");
		}
	}



}
