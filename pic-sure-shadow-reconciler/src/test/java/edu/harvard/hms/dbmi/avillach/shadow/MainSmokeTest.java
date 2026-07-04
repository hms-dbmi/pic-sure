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

    @Test
    void routesFlagFailsGateWhenAListedRouteIsAbsentFromBothLogs(@TempDir Path dir) throws Exception {
        // A healthy allow+reject pair for /query/sync ...
        Path gw = Files.writeString(
            dir.resolve("gw.jsonl"),
            "{\"side\":\"GW\",\"correlationId\":\"c1\",\"channel\":\"introspection\",\"tokenHash\":\"h\","
                + "\"targetService\":\"/query/sync\",\"query\":{\"a\":1},\"formattedQueryPresent\":false,"
                + "\"ipAddress\":null,\"decision\":null}\n"
                + "{\"side\":\"GW\",\"correlationId\":\"c2\",\"channel\":\"introspection\",\"tokenHash\":\"h\","
                + "\"targetService\":\"/query/sync\",\"query\":{\"a\":1},\"formattedQueryPresent\":false,"
                + "\"ipAddress\":null,\"decision\":null}"
        );
        Path wf = Files.writeString(
            dir.resolve("wf.jsonl"),
            "{\"side\":\"WF\",\"correlationId\":\"c1\",\"channel\":\"introspection\",\"tokenHash\":\"h\","
                + "\"targetService\":\"/picsure/query/sync\",\"query\":{\"a\":1},\"formattedQueryPresent\":false,"
                + "\"ipAddress\":null,\"decision\":\"active\"}\n"
                + "{\"side\":\"WF\",\"correlationId\":\"c2\",\"channel\":\"introspection\",\"tokenHash\":\"h\","
                + "\"targetService\":\"/picsure/query/sync\",\"query\":{\"a\":1},\"formattedQueryPresent\":false,"
                + "\"ipAddress\":null,\"decision\":\"inactive\"}"
        );
        // ... but --routes demands a route (/search/R) that neither log ever exercised.
        Path routes = Files.writeString(dir.resolve("routes.txt"), "# canonical routes covered by the stimulus\n/query/sync\n/search/R\n");

        Main.Result withRoutes = Main.execute(new String[] {"--gw", gw.toString(), "--wf", wf.toString(), "--routes", routes.toString()});
        assertEquals(1, withRoutes.exitCode(), "listed-but-unobserved route must fail the gate");

        // Without --routes the same logs pass (every observed route is fully covered).
        Main.Result withoutRoutes = Main.execute(new String[] {"--gw", gw.toString(), "--wf", wf.toString()});
        assertEquals(0, withoutRoutes.exitCode());
    }
}
