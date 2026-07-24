package edu.harvard.hms.dbmi.avillach.query;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * The gateway forwards the user's full privilege set as one X-User-Privileges header, one PRIV_MANAGED_* name per dbGaP consent — for
 * high-consent BDC users that single header exceeds Tomcat's default 8KB request-header budget, which rejects the request with a
 * container-level 400 before any filter or controller runs. This boots the real embedded Tomcat and sends a header block comfortably over
 * the old default: any routed response (here a 404 for an unmapped path) proves Tomcat accepted the headers; a 400 means the raised
 * server.max-http-request-header-size regressed.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LargeRequestHeaderTest {

    @Autowired
    private TestRestTemplate rest;

    @Test
    void acceptsRequestHeadersLargerThanEightKb() {
        HttpHeaders headers = new HttpHeaders();
        StringBuilder privileges = new StringBuilder();
        for (int i = 0; i < 450; i++) {
            if (i > 0) privileges.append(',');
            privileges.append("PRIV_MANAGED_phs").append(String.format("%06d", i)).append("_c1");
        }
        assertThat(privileges.length()).isGreaterThan(8192);
        headers.set("X-User-Privileges", privileges.toString());

        ResponseEntity<String> response =
            rest.exchange("/unmapped-header-size-probe", HttpMethod.GET, new HttpEntity<>(headers), String.class);

        assertThat(response.getStatusCode()).isNotEqualTo(HttpStatus.BAD_REQUEST);
    }
}
