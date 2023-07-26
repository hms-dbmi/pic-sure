package edu.harvard.hms.dbmi.avillach;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.junit.WireMockClassRule;

/**
 * Prettier interface for mocking a http response. Only supports POST rn.
 */
public class ProxyPostEndpointMocker {
    private WireMockClassRule rule;

    private String responseBody;

    private int status;

    private String requestBody;

    private String path;


    public static ProxyPostEndpointMocker start(WireMockClassRule rule) {
        ProxyPostEndpointMocker mock = new ProxyPostEndpointMocker();
        mock.rule = rule;
        return mock;
    }

    public ProxyPostEndpointMocker withPath(String path) {
        this.path = path;
        return this;
    }

    public ProxyPostEndpointMocker withResponseBody(Object body) {
        this.responseBody = toJsonString(body);
        return this;
    }

    public ProxyPostEndpointMocker withRequestBody(Object body) {
        this.requestBody = toJsonString(body);
        return this;
    }

    public ProxyPostEndpointMocker withStatusCode(int status) {
        this.status = status;
        return this;
    }

    public void commit() {
        rule.stubFor(WireMock.post(WireMock.urlEqualTo(path))
            .withRequestBody(WireMock.equalToJson(requestBody))
            .willReturn(WireMock.aResponse()
                .withStatus(status)
                .withBody(responseBody)
            )
        );
    }

    private String toJsonString(Object o) {
        try {
            return new ObjectMapper().writeValueAsString(o);
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

}
