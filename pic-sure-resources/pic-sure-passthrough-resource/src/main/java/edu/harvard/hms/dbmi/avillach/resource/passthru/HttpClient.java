package edu.harvard.hms.dbmi.avillach;

import org.apache.http.Header;
import org.apache.http.HttpResponse;

/**
 * Wrapper class for HttpClientUtil in order used to support unit tests and mocking of responses.
 * 
 * @author nixl5s
 *
 */
public class HttpClient {
	public String composeURL(String baseURL, String pathName) {
		return edu.harvard.dbmi.avillach.util.HttpClientUtil.composeURL(baseURL, pathName);
	}

	public <T> T readObjectFromResponse(HttpResponse response, Class<T> expectedElementType) {
		return edu.harvard.dbmi.avillach.util.HttpClientUtil.readObjectFromResponse(response, expectedElementType);
	}

	public HttpResponse retrieveGetResponse(String uri, Header[] headers) {
		return edu.harvard.dbmi.avillach.util.HttpClientUtil.retrieveGetResponse(uri, headers);
	}

	public HttpResponse retrievePostResponse(String uri, Header[] headers, String body) {
		return edu.harvard.dbmi.avillach.util.HttpClientUtil.retrievePostResponse(uri, headers, body);
	}

	public void throwResponseError(HttpResponse response, String baseURL) {
		edu.harvard.dbmi.avillach.util.HttpClientUtil.throwResponseError(response, baseURL);
	}
}
