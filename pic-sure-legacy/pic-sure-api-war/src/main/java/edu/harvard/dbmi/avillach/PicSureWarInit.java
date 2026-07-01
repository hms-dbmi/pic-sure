package edu.harvard.dbmi.avillach;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import javax.ejb.Singleton;
import javax.enterprise.context.ApplicationScoped;

@Singleton
@ApplicationScoped
public class PicSureWarInit {

    Logger logger = LoggerFactory.getLogger(PicSureWarInit.class);

    @Resource(mappedName = "java:global/token_introspection_url")
    private String token_introspection_url;

    @Resource(mappedName = "java:global/token_introspection_token")
    private String token_introspection_token;

    @Resource(mappedName = "java:global/defaultApplicationUUID")
    private String default_application_uuid;

    @Resource(mappedName = "java:global/openAccessEnabled")
    private String open_access_enabled_str;

    private boolean open_access_enabled;

    @Resource(mappedName = "java:global/openAccessValidateUrl")
    private String open_access_validate_url;

    @PostConstruct
    public void init() {
        this.open_access_enabled = Boolean.parseBoolean(open_access_enabled_str);
        logger.info("Open access enabled: {}", open_access_enabled);
    }

    // to be able to pre modified
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
        CLOSEABLE_HTTP_CLIENT = HttpClients.custom().setConnectionManager(HTTP_CLIENT_CONNECTION_MANAGER).useSystemProperties().build();
    }

    public String getToken_introspection_url() {
        return token_introspection_url;
    }

    public String getToken_introspection_token() {
        return token_introspection_token;
    }

    /**
     * This method is used to get the default application UUID. This value is either the open or auth hpds resource UUID.
     *
     * @return the default application UUID
     */
    public String getDefaultApplicationUUID() {
        return this.default_application_uuid;
    }

    public boolean isOpenAccessEnabled() {
        return open_access_enabled;
    }

    public String getOpenAccessValidateUrl() {
        return this.open_access_validate_url;
    }

}
