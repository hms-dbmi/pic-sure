package edu.harvard.hms.dbmi.avillach.shadow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class RecordParserTest {

    @Test
    void parsesGwIntrospectionLine() {
        String line = "{\"side\":\"GW\",\"correlationId\":\"c1\",\"channel\":\"introspection\","
            + "\"tokenHash\":\"h\",\"targetService\":\"/picsure/query/sync\",\"query\":{\"k\":\"v\"},"
            + "\"formattedQueryPresent\":false,\"ipAddress\":null,\"decision\":null}";
        ShadowRecord r = RecordParser.parseLine(line);
        assertEquals("GW", r.side());
        assertEquals("c1", r.correlationId());
        assertEquals("/picsure/query/sync", r.targetService());
        assertEquals("v", r.query().get("k").asText());
        assertNull(r.decision());
    }

    @Test
    void parsesWfOpenAccessLineWithDecision() {
        String line = "{\"side\":\"WF\",\"correlationId\":\"c2\",\"channel\":\"open-access\","
            + "\"tokenHash\":null,\"targetService\":\"/info/version\",\"query\":null,"
            + "\"formattedQueryPresent\":false,\"ipAddress\":\"OPEN_ACCESS:1.2.3.4\",\"decision\":\"allow\"}";
        ShadowRecord r = RecordParser.parseLine(line);
        assertEquals("WF", r.side());
        assertEquals("open-access", r.channel());
        assertEquals("allow", r.decision());
        assertEquals("OPEN_ACCESS:1.2.3.4", r.ipAddress());
        assertTrue(r.query() == null || r.query().isNull());
    }

    @Test
    void toleratesLogFrameworkPrefixBeforeJson() {
        // e.g. "2026-07-03 10:00:00.000 INFO 1 --- [main] picsure.shadow : {...}"
        String prefix = "2026-07-03 10:00:00.000 INFO 1 --- [main] picsure.shadow : ";
        String json = "{\"side\":\"GW\",\"correlationId\":\"c3\",\"channel\":\"introspection\","
            + "\"tokenHash\":\"h\",\"targetService\":\"/picsure/query/sync\",\"query\":{},"
            + "\"formattedQueryPresent\":false,\"ipAddress\":null,\"decision\":null}";
        ShadowRecord r = RecordParser.parseLine(prefix + json);
        assertEquals("c3", r.correlationId());
    }

    @Test
    void garbageLineThrows() {
        assertThrows(IllegalArgumentException.class, () -> RecordParser.parseLine("not json at all"));
    }

    @Test
    void parseLinesSkipsBlankLines() {
        String gwLine = "{\"side\":\"GW\",\"correlationId\":\"c1\",\"channel\":\"introspection\","
            + "\"tokenHash\":\"h\",\"targetService\":\"/p\",\"query\":{},"
            + "\"formattedQueryPresent\":false,\"ipAddress\":null,\"decision\":null}";
        List<ShadowRecord> records = RecordParser.parseLines(Stream.of("", gwLine, "   ", ""));
        assertEquals(1, records.size());
        assertTrue(records.get(0).correlationId().equals("c1"));
    }
}
