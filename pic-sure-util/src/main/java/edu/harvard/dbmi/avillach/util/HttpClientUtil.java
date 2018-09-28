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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


import javax.ws.rs.NotAuthorizedException;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
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

	public static HttpResponse retrieveGetResponse(String uri, Header[] headers) {
		try {
            logger.debug("HttpClientUtil retrieveGetResponse()");
			HttpClient client = HttpClientBuilder.create().build();
			HttpGet get = new HttpGet(uri);
			// Make the headers optional
			if (headers!=null && headers.length>0) {
				get.setHeaders(headers);
			}
			return client.execute(get);
		} catch (IOException e) {
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

	public static HttpResponse retrievePostResponse(String uri, Header[] headers, String body) {
		try {
		    logger.debug("HttpClientUtil retrievePostResponse()");
			HttpClient client = HttpClientBuilder.create().build();
			HttpPost post = new HttpPost(uri);
            post.setEntity(new StringEntity(body));
            post.setHeaders(headers);
			post.addHeader("Content-type","application/json");
			return client.execute(post);
		} catch (IOException e) {
			//TODO: Write custom exception
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
			throw new RuntimeException("Incorrect list type returned");
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
            throw new RuntimeException("Incorrect object type returned", e);
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
}