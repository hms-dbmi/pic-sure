package edu.harvard.dbmi.avillach;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager;
import org.apache.http.impl.conn.SystemDefaultRoutePlanner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.PostConstruct;
import javax.ejb.ApplicationException;
import javax.ejb.Singleton;
import javax.ejb.Startup;
import javax.enterprise.context.ApplicationScoped;
import javax.naming.Context;
import javax.naming.InitialContext;
import javax.naming.NamingException;
import java.net.ProxySelector;

@Singleton
@Startup
@ApplicationScoped
public class PicSureWarInit {

    Logger logger = LoggerFactory.getLogger(PicSureWarInit.class);

    // decide which authentication method is going to be used
    private String verify_user_method;
    public static final String VERIFY_METHOD_LOCAL="local";
    public static final String VERIFY_METHOD_TOKEN_INTRO="tokenIntrospection";
    private String token_introspection_url;
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
                .setRoutePlanner(
                        new SystemDefaultRoutePlanner(ProxySelector
                                .getDefault()))
                .build();
    }

    @PostConstruct
    public void init() {
        loadTokenIntrospection();
    }

    private void loadTokenIntrospection(){
        try {
            Context ctx = new InitialContext();
            verify_user_method = (String) ctx.lookup("global/verify_user_method");
            token_introspection_url = (String) ctx.lookup("global/token_introspection_url");
            token_introspection_token = (String) ctx.lookup("global/token_introspection_token");
            ctx.close();
        } catch (NamingException e) {
            verify_user_method = VERIFY_METHOD_LOCAL;
        }

        logger.info("verify_user_method setup as: " + verify_user_method);
    }

    public String getToken_introspection_url() {
        return token_introspection_url;
    }

    public void setToken_introspection_url(String token_introspection_url) {
        this.token_introspection_url = token_introspection_url;
    }

    public String getToken_introspection_token() {
        return token_introspection_token;
    }

    public void setToken_introspection_token(String token_introspection_token) {
        this.token_introspection_token = token_introspection_token;
    }

    public String getVerify_user_method() {
        return verify_user_method;
    }

    public void setVerify_user_method(String verify_user_method) {
        this.verify_user_method = verify_user_method;
    }
}
