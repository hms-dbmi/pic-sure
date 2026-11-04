package edu.harvard.hms.dbmi.avillach.gateway.docs;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** Only the servers array changes; the key stays where the upstream put it, or is appended when the upstream had none. */
class ServersRewriterTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void replacesServersAndTouchesNothingElse() throws Exception {
        ObjectNode upstream = (ObjectNode) JSON.readTree(
            "{\"openapi\":\"3.0.1\",\"servers\":[{\"url\":\"http://upstream:8080\"},{\"url\":\"http://other\"}],\"paths\":{\"/x\":{}}}"
        );
        ObjectNode expectedRest = upstream.deepCopy();
        expectedRest.remove("servers");

        ObjectNode served = ServersRewriter.rewrite(upstream.deepCopy(), "/picsure/demo");

        assertThat(served.get("servers").size()).isEqualTo(1);
        assertThat(served.get("servers").get(0).get("url").asText()).isEqualTo("/picsure/demo");
        ObjectNode servedRest = served.deepCopy();
        servedRest.remove("servers");
        assertThat(servedRest).isEqualTo(expectedRest);
        assertThat(served.fieldNames().next()).isEqualTo("openapi");
    }

    @Test
    void addsServersWhenTheUpstreamHadNone() throws Exception {
        ObjectNode upstream = (ObjectNode) JSON.readTree("{\"openapi\":\"3.0.1\",\"paths\":{}}");
        ObjectNode served = ServersRewriter.rewrite(upstream, "/picsure");
        assertThat(served.get("servers").get(0).get("url").asText()).isEqualTo("/picsure");
    }
}
