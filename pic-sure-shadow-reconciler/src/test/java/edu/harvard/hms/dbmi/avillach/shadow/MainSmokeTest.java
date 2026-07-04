package edu.harvard.hms.dbmi.avillach.shadow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MainSmokeTest {

    @Test
    void runReturnsReportAndExitCode(@TempDir Path dir) throws Exception {
        Path gw = Files.writeString(
            dir.resolve("gw.jsonl"),
            "{\"side\":\"GW\",\"correlationId\":\"c1\",\"channel\":\"introspection\",\"tokenHash\":\"h\","
                + "\"targetService\":\"/query/sync\",\"query\":{\"a\":1},\"formattedQueryPresent\":false,"
                + "\"ipAddress\":null,\"decision\":null}"
        );
        Path wf = Files.writeString(
            dir.resolve("wf.jsonl"),
            "{\"side\":\"WF\",\"correlationId\":\"c1\",\"channel\":\"introspection\",\"tokenHash\":\"h\","
                + "\"targetService\":\"/picsure/query/sync\",\"query\":{\"a\":1},\"formattedQueryPresent\":false,"
                + "\"ipAddress\":null,\"decision\":\"active\"}"
        );
        Main.Result res = Main.execute(new String[] {"--gw", gw.toString(), "--wf", wf.toString()});
        assertTrue(res.report().render().contains("EXIT GATE"));
        // single allow, no reject -> gate fails -> exit 1
        assertEquals(1, res.exitCode());
    }
}
