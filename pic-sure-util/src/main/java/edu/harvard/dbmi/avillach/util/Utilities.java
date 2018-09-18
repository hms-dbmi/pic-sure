package edu.harvard.dbmi.avillach.util;

import org.apache.http.HttpHost;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.HttpRequestBase;
import org.apache.http.client.protocol.HttpClientContext;
import org.apache.http.impl.client.BasicCredentialsProvider;

public class Utilities {

    /**
     * to apply a proxy to a http function by reading the environment variables from JVM
     * @param request
     */
    public static void applyProxySettings(HttpRequestBase request) {
        String proxyHost = System.getProperty("http.proxyHost");
        String proxyPort = System.getProperty("http.proxyPort");
        String proxyProtocol = System.getProperty("http.proxyProtocol"); // non-standard
        if (proxyHost != null) {
            int port = 80;
            if (proxyPort != null) {
                port = Integer.parseInt(proxyPort);
            }

            if (proxyProtocol == null) {
                proxyProtocol = "http";
                if (port == 443) {
                    proxyProtocol = "https";
                }
            }

            HttpHost proxy = new HttpHost(proxyHost, port, proxyProtocol);
            RequestConfig requestConfig = RequestConfig.custom().setProxy(proxy).build();
            request.setConfig(requestConfig);
        }
    }

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
}
