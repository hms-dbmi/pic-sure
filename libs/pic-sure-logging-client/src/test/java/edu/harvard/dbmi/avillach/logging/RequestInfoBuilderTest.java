package edu.harvard.dbmi.avillach.logging;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.harvard.dbmi.avillach.contracts.audit.RequestInfo;

import org.junit.jupiter.api.Test;

class RequestInfoBuilderTest {

    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * The builder exists only so no emitter has to write out thirteen positional arguments. If it ever disagrees with the canonical
     * constructor about which value belongs in which slot, every audit record it produces is silently wrong.
     */
    @Test
    void buildsEveryFieldIntoTheSameSlotAsTheCanonicalConstructor() {
        RequestInfo built = new RequestInfoBuilder().requestId("req-123").method("POST").url("/query/sync").queryString("limit=100")
            .srcIp("10.0.0.1").destIp("10.0.0.2").destPort(8080).httpUserAgent("PIC-SURE/3.0").httpContentType("application/json")
            .status(200).bytes(4096L).duration(250L).referrer("https://picsure.example.edu").build();

        assertEquals(
            new RequestInfo(
                "req-123", "POST", "/query/sync", "limit=100", "10.0.0.1", "10.0.0.2", 8080, "PIC-SURE/3.0", "application/json", 200, 4096L,
                250L, "https://picsure.example.edu"
            ), built
        );
    }

    /**
     * The filters fill in a handful of these fields and leave the rest alone; what they leave alone must not reach the wire.
     */
    @Test
    void leavesUnsetFieldsOffTheWire() throws Exception {
        RequestInfo sparse = new RequestInfoBuilder().method("POST").url("/picsure/query/sync").status(200).build();

        assertEquals("{\"method\":\"POST\",\"url\":\"/picsure/query/sync\",\"status\":200}", mapper.writeValueAsString(sparse));
    }
}
