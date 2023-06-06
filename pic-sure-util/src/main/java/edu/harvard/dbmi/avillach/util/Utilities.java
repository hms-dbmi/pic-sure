package edu.harvard.dbmi.avillach.util;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.client.protocol.HttpClientContext;
import org.apache.http.impl.client.BasicCredentialsProvider;

import javax.ws.rs.core.HttpHeaders;
import java.util.Optional;

public class Utilities {

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

    public static String getRequestSourceFromHeader(HttpHeaders headers) {
        if (headers == null) return "";
        return Optional.ofNullable(headers.getHeaderString("request-source")).orElse("");
    }

    public static String convertQueryRequestToString(ObjectMapper mapper, Object searchQueryRequest) {
        if (mapper == null) return "";
        if (searchQueryRequest == null) return "";
        try {
            return mapper.writeValueAsString(searchQueryRequest);
        } catch (JsonProcessingException e) {
            // We don't want to stop code execution if this fails
            return "";
        }
    }

}
