package edu.harvard.dbmi.avillach.util;

import static edu.harvard.dbmi.avillach.util.Utilities.buildHttpClientContext;

import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.concurrent.TimeoutException;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import javax.net.ssl.SSLContext;
import javax.ws.rs.NotAuthorizedException;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.MediaType;

import org.apache.commons.io.IOUtils;
import org.apache.http.Header;
import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.conn.ssl.SSLConnectionSocketFactory;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.message.BasicHeader;
import org.apache.http.ssl.SSLContexts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import edu.harvard.dbmi.avillach.util.exception.ApplicationException;
import edu.harvard.dbmi.avillach.util.exception.ResourceInterfaceException;

public class HttpClientUtil {
	private static final ObjectMapper json = new ObjectMapper();

	private static final Logger logger = LoggerFactory.getLogger(HttpClientUtil.class);

	public static boolean is2xx(HttpResponse response) {
		return response.getStatusLine().getStatusCode() / 100 == 2;
	}

	public static HttpResponse retrieveGetResponse(String uri, List<Header> headers) {
		return retrieveGetResponse(uri, headers.toArray(new Header[headers.size()]));
	}

	/**
	 * resource level get, which will throw a <b>ResourceInterfaceException</b> if cannot get response back from the url
	 * 
	 * @param uri
	 * @param headers
	 * @return
	 */
	public static HttpResponse retrieveGetResponse(String uri, Header[] headers) {
		try {
			logger.debug("HttpClientUtil retrieveGetResponse()");

			HttpClient client = getConfiguredHttpClient();
			return simpleGet(client, uri, headers);
		} catch (ApplicationException e) {
			throw new ResourceInterfaceException(uri, e);
		}
	}

	public static String composeURL(String baseURL, String pathName) {
		return composeURL(baseURL, pathName, null);
	}

	public static String composeURL(String baseURL, String pathName, String query) {
		try {
			URI uri = new URI(baseURL);
			List<String> basePathComponents = Arrays.asList(uri.getPath().split("/"));
			List<String> pathNameComponents = Arrays.asList(pathName.split("/"));
			List<String> allPathComponents = new LinkedList<>();
			Predicate<? super String> nonEmpty = (segment) -> {
				return !segment.isEmpty();
			};
			allPathComponents.addAll(basePathComponents.stream().filter(nonEmpty).collect(Collectors.toList()));
			allPathComponents.addAll(pathNameComponents.stream().filter(nonEmpty).collect(Collectors.toList()));
			String queryString = query == null ? uri.getQuery() : query;
			return new URI(uri.getScheme(), uri.getUserInfo(), uri.getHost(), uri.getPort(),
					"/" + String.join("/", allPathComponents), queryString, uri.getFragment()).toString();
		} catch (URISyntaxException e) {
			throw new ApplicationException("baseURL invalid : " + baseURL, e);
		}
	}

	/**
	 * resource level post, which will throw a <b>ResourceInterfaceException</b> if cannot get response back from the
	 * url
	 * 
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

			HttpClient client = getConfiguredHttpClient();
			return simplePost(uri, client, new StringEntity(body), headerList.toArray(new Header[headerList.size()]));
		} catch (ApplicationException | UnsupportedEncodingException e) {
			throw new ResourceInterfaceException(uri, e);
		}
	}

	public static HttpResponse retrievePostResponse(String uri, List<Header> headers, String body) {
		return retrievePostResponse(uri, headers.toArray(new Header[headers.size()]), body);
	}

	public static <T> List<T> readListFromResponse(HttpResponse response, Class<T> expectedElementType) {
		logger.debug("HttpClientUtil readListFromResponse()");
		try {
			String responseBody = IOUtils.toString(response.getEntity().getContent(), StandardCharsets.UTF_8);
			return json.readValue(responseBody, new TypeReference<List<T>>() {
			});
		} catch (IOException e) {
			throw new ApplicationException("Incorrect list type returned");
		}
	}

	public static String readObjectFromResponse(HttpResponse response) {
		logger.debug("HttpClientUtil readObjectFromResponse(HttpResponse response)");
		try {
			String responseBody = IOUtils.toString(response.getEntity().getContent(), StandardCharsets.UTF_8);
			logger.debug("readObjectFromResponse() responseBody {}", responseBody);
			return responseBody;
		} catch (IOException e) {
			throw new ApplicationException("Incorrect object type returned", e);
		}
	}

	public static <T> T readObjectFromResponse(HttpResponse response, Class<T> expectedElementType) {
		logger.debug("HttpClientUtil readObjectFromResponse()");
		try {
			long startTime = System.nanoTime();
			String responseBody = IOUtils.toString(response.getEntity().getContent(), StandardCharsets.UTF_8);
			logger.debug(
					"readObjectFromResponse() line: IOUtils.toString(response.getEntity().getContent(), \"UTF-8\"), took {}",
					(System.nanoTime() - startTime));
			logger.trace("readObjectFromResponse() responseBody {}", responseBody);

			startTime = System.nanoTime();
			T t = json.readValue(responseBody, json.getTypeFactory().constructType(expectedElementType));
			logger.debug(
					"readObjectFromResponse() line: json.readValue(responseBody, json.getTypeFactory().constructType(expectedElementType)), took {}",
					(System.nanoTime() - startTime));
			return t;
		} catch (IOException e) {
			throw new ApplicationException("Incorrect object type returned", e);
		}
	}

	public static void throwResponseError(HttpResponse response, String baseURL) {
		String errorMessage = baseURL + " " + response.getStatusLine().getStatusCode() + " "
				+ response.getStatusLine().getReasonPhrase();
		try {
			JsonNode responseNode = json.readTree(response.getEntity().getContent());
			if (responseNode != null && responseNode.has("message")) {
				errorMessage += "/n" + responseNode.get("message").asText();
			}
		} catch (IOException e) {
			// That's fine, there's no message
		}
		if (response.getStatusLine().getStatusCode() == 401) {
			throw new NotAuthorizedException(errorMessage);
		}
		throw new ResourceInterfaceException(errorMessage);
	}

	public static void throwInternalResponseError(HttpResponse response, String baseURL) {
		// We don't want to propagate 401s. A site 401ing is a server side error and should
		// 500 in the common area.
		String errorMessage = baseURL + " " + response.getStatusLine().getStatusCode() + " "
			+ response.getStatusLine().getReasonPhrase();
		try {
			JsonNode responseNode = json.readTree(response.getEntity().getContent());
			if (responseNode != null && responseNode.has("message")) {
				errorMessage += "/n" + responseNode.get("message").asText();
			}
		} catch (IOException e) {
			// That's fine, there's no message
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
			throws ApplicationException {
		if (client == null) {
			client = getConfiguredHttpClient();
		}

		HttpPost post = new HttpPost(uri);
		post.setHeaders(headers);
		post.setEntity(requestBody);

		try {
			return client.execute(post, buildHttpClientContext());
		} catch (IOException ex) {
			logger.error("simplePost() Exception: {}, cannot get response by POST from url: {}", ex.getMessage(), uri);
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
	public static InputStream simplePost(String uri, StringEntity requestBody, HttpClient client, Header... headers) {
		HttpResponse response = simplePost(uri, client, requestBody, headers);

		try {
			return response.getEntity().getContent();
		} catch (IOException ex) {
			logger.error("simplePost() cannot get content by POST from url: {} - " + ex.getLocalizedMessage(), uri);
			throw new ApplicationException("Inner problem, please contact system admin and check the server log");
		}
	}

	/**
	 * only works if the POST method returns a JSON response body
	 * 
	 * @param uri
	 * @param requestBody
	 * @param client
	 * @param objectMapper
	 * @param headers
	 * @return
	 */
	public static JsonNode simplePost(String uri, StringEntity requestBody, HttpClient client,
			ObjectMapper objectMapper, Header... headers) {
		try {
			return objectMapper.readTree(simplePost(uri, requestBody, client, headers));
		} catch (IOException ex) {
			ex.printStackTrace();
			logger.error("simplePost() Exception: {}, cannot parse content from by POST from url: {}", ex,
					uri);
			StackTraceElement[] stackTraceElements = e2.getStackTrace();
			try {
			    	String logMessage = "IOException occured in method "
				    + stackTraceElements[0].getMethodName() + " - File name is "
				    + stackTraceElements[0].getFileName()
				    + " - At line number: "
				    + stackTraceElements[0].getLineNumber();
				logger.error(logMessage);
			} catch (Exception ex) {
				logger.error(ex);
			}
			throw new ApplicationException("Inner problem, please contact system admin and check the server log");
		}
	}

	/**
	 * for general and basic use of GET function using Apache Http Client
	 * 
	 * @param client
	 * @param uri
	 * @param headers
	 * @return
	 * @throws ApplicationException
	 */
	public static HttpResponse simpleGet(HttpClient client, String uri, Header... headers) throws ApplicationException {
		if (client == null) {
			client = getConfiguredHttpClient();
		}

		HttpGet get = new HttpGet(uri);
		get.setHeaders(headers);

		try {
			return client.execute(get, buildHttpClientContext());
		} catch (IOException ex) {
			logger.error("HttpResponse simpleGet() cannot get response by GET from url: {} - " + ex.getLocalizedMessage(), uri);
			throw new ApplicationException("Inner problem, please contact system admin and check the server log");
		}
	}

	public static InputStream simpleGet(String uri, HttpClient client, Header... headers) {
		return simpleGetWithConfig(uri, client, null, headers);
	}

	public static InputStream simpleGetWithConfig(
		String uri, HttpClient client, RequestConfig config, Header... headers
	) throws ApplicationException {
		HttpGet get = new HttpGet(uri);
		get.setHeaders(headers);
		if (config != null) {
			get.setConfig(config);
		}

		try {
			return client.execute(get, buildHttpClientContext())
				.getEntity()
				.getContent();
		} catch (IOException ex) {
			logger.error("InputStream simpleGet() cannot get response by GET from url: {} - " + ex.getLocalizedMessage(), uri);
			throw new ApplicationException("Inner problem, please contact system admin and check the server log");
		}
	}

	/**
	 * only work if the GET method returns a JSON response body
	 * 
	 * @param uri
	 * @param client
	 * @param objectMapper
	 * @param headers
	 * @return
	 */
	public static JsonNode simpleGet(String uri, HttpClient client, ObjectMapper objectMapper, Header... headers) {
		try {
			return objectMapper.readTree(simpleGet(uri, client, headers));
		} catch (IOException ex) {
			logger.error("simpleGet() cannot parse content from by GET from url: {}", uri, ex);
			throw new ApplicationException("Inner problem, please contact system admin and check the server log");
		}
	}

	public static JsonNode simpleGetWithConfig(
		String uri, HttpClient client, ObjectMapper objectMapper, RequestConfig requestConfig, Header... headers
	) {
		try {
			return objectMapper.readTree(simpleGetWithConfig(uri, client, requestConfig, headers));
		} catch (IOException ex) {
			logger.error("simpleGet() cannot parse content from by GET from url: {}", uri, ex);
			throw new ApplicationException("Inner problem, please contact system admin and check the server log");
		}
	}
	
	public static HttpClient getConfiguredHttpClient() {
		try {
			SSLConnectionSocketFactory.getSocketFactory();
			SSLContext sslContext = SSLContext.getInstance("TLSv1.2");
		    sslContext.init(null, null, null);
		    String[] defaultCiphers = sslContext.getServerSocketFactory().getDefaultCipherSuites();
			
			List<String> limited = new LinkedList<String>();
			for(String suite : defaultCiphers)
			{
				//filter out Diffie-Hellman ciphers
			    if( ! (suite.contains("_DHE_") || suite.contains("_DH_")))
			    {
			        limited.add(suite);
			    }
			}
			
			return HttpClients.custom()
				    .setSSLSocketFactory(new SSLConnectionSocketFactory(
			            SSLContexts.createSystemDefault(),
			            new String[]{"TLSv1.2"},
			            limited.toArray(new String[limited.size()]),
			            SSLConnectionSocketFactory.getDefaultHostnameVerifier()))
				    .build();
		} catch( NoSuchAlgorithmException | KeyManagementException e) {
			logger.warn("Unable to establish SSL context.  using default client", e);
		}
		
		//default
		return HttpClientBuilder.create().useSystemProperties().build();
	}
}
