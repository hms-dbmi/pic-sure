package edu.harvard.dbmi.avillach.util;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.harvard.dbmi.avillach.util.exception.ApplicationException;
import edu.harvard.dbmi.avillach.util.exception.ResourceInterfaceException;
import org.apache.commons.io.IOUtils;
import org.apache.http.Header;
import org.apache.http.HttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.client.HttpClient;
import org.apache.http.message.BasicHeader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


import javax.ws.rs.NotAuthorizedException;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.MediaType;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.function.Predicate;
import java.util.stream.Collectors;

public class HttpClientUtil {
	private final static ObjectMapper json = new ObjectMapper();

	private final static Logger logger = LoggerFactory.getLogger(HttpClientUtil.class);


	public static HttpResponse retrieveGetResponse(String uri, List<Header> headers) {
		return retrieveGetResponse(uri, headers.toArray(new Header[headers.size()]));
	}

	/**
	 * resource level get, which will throw a <b>ResourceInterfaceException</b> if cannot get response back from the url
	 * @param uri
	 * @param headers
	 * @return
	 */
	public static HttpResponse retrieveGetResponse(String uri, Header[] headers) {
		try {
            logger.debug("HttpClientUtil retrieveGetResponse()");

			HttpClient client = HttpClientBuilder.create().build();
            return simpleGet(client, uri, headers);
		} catch (ApplicationException e) {
			//TODO: Write custom exception
			throw new ResourceInterfaceException(uri, e);
		}
	}
	
	public static String composeURL(String baseURL, String pathName) {
	    return composeURL(baseURL, pathName, null);
	}

	public static String composeURL(String baseURL, String pathName, String query) {
		URI uri;
		try {
			uri = new URI(baseURL);
			List<String> basePathComponents = Arrays.asList(uri.getPath().split("/"));
			List<String> pathNameComponents = Arrays.asList(pathName.split("/"));
			List<String> allPathComponents = new LinkedList<String>();
			Predicate<? super String> nonEmpty = (segment)->{
				return ! segment.isEmpty();
			};
			allPathComponents.addAll(basePathComponents.stream().filter(nonEmpty).collect(Collectors.toList()));
			allPathComponents.addAll(pathNameComponents.stream().filter(nonEmpty).collect(Collectors.toList()));
			String queryString = query == null? uri.getQuery() : query;
			return new URI(uri.getScheme(), uri.getUserInfo(), uri.getHost(), uri.getPort(),"/" + String.join("/", allPathComponents), queryString, uri.getFragment()).toString();
		} catch (URISyntaxException e) {
			throw new ApplicationException("baseURL invalid : " + baseURL, e);
		}
	}

	/**
	 * resource level post, which will throw a <b>ResourceInterfaceException</b> if cannot get response back from the url
	 * @param uri
	 * @param headers
	 * @return
	 */
	public static HttpResponse retrievePostResponse(String uri, Header[] headers, String body) {
		try {
		    logger.debug("HttpClientUtil retrievePostResponse()");

		    List<Header> headerList = new ArrayList<>();

		    if (headers != null)
		    	headerList = new ArrayList<>(Arrays.asList(headers));
		    headerList.add(new BasicHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON));

			HttpClient client = HttpClientBuilder.create().build();
		    return simplePost(uri, client, new StringEntity(body), headerList.toArray(new Header[headerList.size()]));
		} catch (ApplicationException | UnsupportedEncodingException e) {
			//TODO: Write custom exception
			logger.error("retrievePostResponse() throw resourceInterfaceException: " + e.getClass().getSimpleName() + ": " + e.getMessage());
			throw new ResourceInterfaceException(uri, e);
		}
	}

	public static HttpResponse retrievePostResponse(String uri, List<Header> headers, String body) {
		return retrievePostResponse(uri, headers.toArray(new Header[headers.size()]), body);
	}

	public static <T> List<T> readListFromResponse(HttpResponse response, Class<T> expectedElementType) {
        logger.debug("HttpClientUtil readListFromResponse()");
        try {
			String responseBody = IOUtils.toString(response.getEntity().getContent(), "UTF-8");
			return json.readValue(responseBody, new TypeReference<List<T>>() {});
		} catch (IOException e) {
      logger.error("readListFromResponse() "+e.getMessage());
      //TODO: Write custom exception
			throw new ApplicationException("Incorrect list type returned");
		}
	}

	public static String readObjectFromResponse(HttpResponse response) {
		logger.debug("HttpClientUtil readObjectFromResponse(HttpResponse response)");
		try {
			String responseBody = IOUtils.toString(response.getEntity().getContent(), "UTF-8");
			logger.debug("readObjectFromResponse() responseBody "+responseBody);
			return responseBody;
		} catch (IOException e) {
			logger.error("readObjectFromResponse() "+e.getMessage());
			//TODO: Write custom exception
			throw new ApplicationException("Incorrect object type returned", e);
		}
	}

    public static <T> T readObjectFromResponse(HttpResponse response, Class<T> expectedElementType) {
        logger.debug("HttpClientUtil readObjectFromResponse()");
        try {
            String responseBody = IOUtils.toString(response.getEntity().getContent(), "UTF-8");
            logger.debug("readObjectFromResponse() responseBody "+responseBody);
            return json.readValue(responseBody, json.getTypeFactory().constructType(expectedElementType));
        } catch (IOException e) {
            logger.error("readObjectFromResponse() "+e.getMessage());
            //TODO: Write custom exception
            throw new ApplicationException("Incorrect object type returned", e);
        }
    }

	public static void throwResponseError(HttpResponse response, String baseURL){
		String errorMessage = baseURL + " " + response.getStatusLine().getStatusCode() + " " + response.getStatusLine().getReasonPhrase();
		try {
			JsonNode responseNode = json.readTree(response.getEntity().getContent());
			if (responseNode != null && responseNode.has("message")){
					errorMessage += "/n" + responseNode.get("message").asText();
				}
			} catch (IOException e ){
			//That's fine, there's no message
			}
		if (response.getStatusLine().getStatusCode() == 401) {
			throw new NotAuthorizedException(errorMessage);
		}
		throw new ResourceInterfaceException(errorMessage);
	}

	/**
	 * Basic and general post function using Apache Http Client
	 *
	 * @param uri
	 * @param client
	 * @param requestBody
	 * @param headers
	 * @return HttpResponse
	 * @throws ApplicationException
	 */
	public static HttpResponse simplePost(String uri, HttpClient client, StringEntity requestBody, Header... headers)
			throws ApplicationException{

		if (client == null)
			client = HttpClientBuilder.create().build();

		HttpPost post = new HttpPost(uri);
		post.setHeaders(headers);
		post.setEntity(requestBody);

		try {
			return client.execute(post);
		} catch (IOException ex){
			logger.error("simplePost() Exception: " + ex.getMessage() +
					", cannot get response by POST from url: " + uri);
			throw new ApplicationException("Inner problem, please contact system admin and check the server log");
		}
	}

	/**
	 * Basic and general post function using Apache Http Client
	 *
	 * @param uri
	 * @param requestBody
	 * @param client
	 * @param headers
	 * @return InputStream
	 */
	public static InputStream simplePost(String uri, StringEntity requestBody, HttpClient client, Header... headers){

		HttpResponse response = simplePost(uri, client, requestBody, headers);

		try {
			return response.getEntity().getContent();
		} catch (IOException ex){
			logger.error("simplePost() cannot get content by POST from url: " + uri);
			throw new ApplicationException("Inner problem, please contact system admin and check the server log");
		}
	}

	/**
	 * only works if the POST method returns a JSON response body
	 * @param uri
	 * @param requestBody
	 * @param client
	 * @param objectMapper
	 * @param headers
	 * @return
	 */
	public static JsonNode simplePost(String uri, StringEntity requestBody, HttpClient client, ObjectMapper objectMapper, Header... headers){
		try {
			return objectMapper.readTree(simplePost(uri, requestBody, client, headers));
		} catch (IOException ex){
			logger.error("simplePost() Exception: " + ex.getMessage()
					+ ", cannot parse content from by POST from url: " + uri);
			throw new ApplicationException("Inner problem, please contact system admin and check the server log");
		}
	}

	/**
	 * for general and basic use of GET function using Apache Http Client
	 * @param client
	 * @param uri
	 * @param headers
	 * @return
	 * @throws ApplicationException
	 */
	public static HttpResponse simpleGet(HttpClient client, String uri, Header... headers)
			throws ApplicationException{

		if (client == null)
			client = HttpClientBuilder.create().build();

		HttpGet get = new HttpGet(uri);
		get.setHeaders(headers);

		HttpResponse response;

		try {
			return client.execute(get);
		} catch (IOException ex){
			logger.error("simpleGet() cannot get response by GET from url: " + uri);
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

	/**
	 * only work if the GET method returns a JSON response body
	 * @param uri
	 * @param client
	 * @param objectMapper
	 * @param headers
	 * @return
	 */
	public static JsonNode simpleGet(String uri, HttpClient client, ObjectMapper objectMapper, Header... headers){
		try {
			return objectMapper.readTree(simpleGet(uri, client, headers));
		} catch (IOException ex){
			logger.error("simpleGet() cannot parse content from by GET from url: " + uri, ex);
			throw new ApplicationException("Inner problem, please contact system admin and check the server log");
		}
	}
}
