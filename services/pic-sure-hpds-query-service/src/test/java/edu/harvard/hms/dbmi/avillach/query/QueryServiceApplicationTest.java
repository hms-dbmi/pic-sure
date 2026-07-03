package edu.harvard.hms.dbmi.avillach.query;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.web.client.RestClient;

import edu.harvard.hms.dbmi.avillach.query.hpds.HpdsBackendSelector;
import edu.harvard.hms.dbmi.avillach.query.operations.OperationsClient;

/**
 * Context-load smoke test: the module must boot with the HPDS + operations-service URLs pointed at dummy values (see
 * src/test/resources/application.yml) and the DB-free beans (HpdsBackendSelector, the pooled HPDS RestClient, OperationsClient, the pooled
 * operations RestClient) must all be wired. No DB, no H2 -- this service owns none.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
class QueryServiceApplicationTest {

    @Autowired
    private ApplicationContext context;

    @Autowired
    private HpdsBackendSelector hpdsBackendSelector;

    @Autowired
    private OperationsClient operationsClient;

    @Autowired
    @org.springframework.beans.factory.annotation.Qualifier("hpdsClient")
    private RestClient hpdsClient;

    @Autowired
    @org.springframework.beans.factory.annotation.Qualifier("operationsRestClient")
    private RestClient operationsRestClient;

    @Test
    void contextLoads() {
        assertThat(context).isNotNull();
    }

    @Test
    void hpdsBackendSelectorIsWired() {
        assertThat(hpdsBackendSelector).isNotNull();
        var target = hpdsBackendSelector.select("auth", false);
        assertThat(target.baseUrl()).isEqualTo("http://localhost:1/PIC-SURE");
        assertThat(target.token()).isEqualTo("test-auth-token");
    }

    @Test
    void operationsClientIsWired() {
        assertThat(operationsClient).isNotNull();
    }

    @Test
    void restClientBeansAreDistinctAndWired() {
        assertThat(hpdsClient).isNotNull();
        assertThat(operationsRestClient).isNotNull();
        assertThat(hpdsClient).isNotSameAs(operationsRestClient);
    }
}
