package edu.harvard.dbmi.avillach;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager;
import org.apache.http.impl.conn.SystemDefaultRoutePlanner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Resource;
import javax.ejb.Singleton;
import javax.enterprise.context.ApplicationScoped;
import java.net.ProxySelector;

@Singleton
@ApplicationScoped
public class PicSureWarInit {

    Logger logger = LoggerFactory.getLogger(PicSureWarInit.class);

    @Resource(mappedName = "java:global/token_introspection_url")
    private String token_introspection_url;

    @Resource(mappedName = "java:global/token_introspection_token")
    private String token_introspection_token;

    //to be able to pre modified
    public static final ObjectMapper objectMapper = new ObjectMapper();

    // check the example from Apache HttpClient official website:
    // http://hc.apache.org/httpcomponents-client-4.5.x/httpclient/examples/org/apache/http/examples/client/ClientMultiThreadedExecution.java
    public static final PoolingHttpClientConnectionManager HTTP_CLIENT_CONNECTION_MANAGER;

    // If want to use self sign certificate for https,
    // please follow the official httpclient example link:
    // https://hc.apache.org/httpcomponents-client-ga/httpclient/examples/org/apache/http/examples/client/ClientCustomSSL.java
    public static final CloseableHttpClient CLOSEABLE_HTTP_CLIENT;
    static {
        HTTP_CLIENT_CONNECTION_MANAGER = new PoolingHttpClientConnectionManager();
        HTTP_CLIENT_CONNECTION_MANAGER.setMaxTotal(100);
        CLOSEABLE_HTTP_CLIENT = HttpClients
                .custom()
                .setConnectionManager(HTTP_CLIENT_CONNECTION_MANAGER)
                .useSystemProperties()
                .build();
    }


    public String getToken_introspection_url() {
        return token_introspection_url;
    }

    public String getToken_introspection_token() {
        return token_introspection_token;
    }
}
