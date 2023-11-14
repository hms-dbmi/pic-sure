package edu.harvard.dbmi.avillach.util;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.client.protocol.HttpClientContext;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.ws.rs.core.HttpHeaders;
import java.util.Optional;

public class Utilities {

    private static final Logger logger = LoggerFactory.getLogger(Utilities.class);

    public static HttpClientContext buildHttpClientContext() {
        HttpClientContext httpClientContext = null;
        String proxyUser = System.getProperty("http.proxyUser"); // non-standard
        String proxyPass = System.getProperty("http.proxyPassword"); // non-standard
        if (proxyUser != null && proxyPass != null) {
            httpClientContext =  HttpClientContext.create();
            CredentialsProvider credentialsProvider = new BasicCredentialsProvider();
            credentialsProvider.setCredentials(AuthScope.ANY, new UsernamePasswordCredentials(proxyUser, proxyPass));
            httpClientContext.setCredentialsProvider(credentialsProvider);
        }
        return httpClientContext;
    }

    /**
     * This method is used to get the request source from the request header. It is used for logging purposes.
     *
     * @param headers the request headers
     * @return the request source
     */
    public static String getRequestSourceFromHeader(HttpHeaders headers) {
        if (headers == null) return "headers are null";
        return headers.getHeaderString("request-source") == null ? "request-source header is null" : headers.getHeaderString("request-source");
    }

    public static String convertQueryRequestToString(ObjectMapper mapper, Object searchQueryRequest) {
        if (mapper == null || searchQueryRequest == null) {
            logger.info("Error converting query request to string: mapper or searchQueryRequest is null");
            return "";
        }
        try {
            return mapper.writeValueAsString(searchQueryRequest);
        } catch (JsonProcessingException e) {
            logger.info("Error converting query request to string: " + e.getMessage());
            return "";
        }
    }

}
