package edu.harvard.hms.dbmi.avillach.resource.passthru;

import javax.enterprise.context.ApplicationScoped;

import org.apache.http.Header;
import org.apache.http.HttpResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import edu.harvard.dbmi.avillach.util.exception.ResourceInterfaceException;

/**
 * Wrapper class for HttpClientUtil in order used to support unit tests and mocking of responses.
 * 
 * @author nixl5s
 *
 */
@ApplicationScoped
public class HttpClient {
	private static final int RETRY_LIMIT = 5;
	
	private static final Logger logger = LoggerFactory.getLogger(HttpClient.class);

	public String composeURL(String baseURL, String pathName) {
		return edu.harvard.dbmi.avillach.util.HttpClientUtil.composeURL(baseURL, pathName);
	}

	public <T> T readObjectFromResponse(HttpResponse response, Class<T> expectedElementType) {
		return edu.harvard.dbmi.avillach.util.HttpClientUtil.readObjectFromResponse(response, expectedElementType);
	}

//	public HttpResponse retrieveGetResponse(String uri, Header[] headers) {
//		return edu.harvard.dbmi.avillach.util.HttpClientUtil.retrieveGetResponse(uri, headers);
//	}

	public HttpResponse retrievePostResponse(String uri, Header[] headers, String body) {
		
		HttpResponse response = null;
		for(int i = 1; i <= RETRY_LIMIT && response == null; i++) {
	         try {
	        	 response = edu.harvard.dbmi.avillach.util.HttpClientUtil.retrievePostResponse(uri, headers, body);
	        	 break;
	         } catch (ResourceInterfaceException e) {
	        	 if(i < RETRY_LIMIT ) {
	        		 logger.warn("Failed to contact remote server.  Retrying");
	        	 } else {
	        		 logger.error("Failed to contact remote server.  Giving up!");
	        		 throw e;
	        	 }
	         }
       }
		
		return response;
	}

	public void throwResponseError(HttpResponse response, String baseURL) {
		edu.harvard.dbmi.avillach.util.HttpClientUtil.throwResponseError(response, baseURL);
	}
}
