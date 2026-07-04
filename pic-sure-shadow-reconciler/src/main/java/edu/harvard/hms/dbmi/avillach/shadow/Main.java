package edu.harvard.hms.dbmi.avillach.shadow;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * CLI entrypoint for the offline reconciler: reads a GW and a WF shadow-log JSONL file, runs the full
 * parse-&gt;join-&gt;classify-&gt;aggregate pipeline, prints the {@link Report}, and exits {@code 0} when the observe-window exit gate is
 * green, {@code 1} otherwise (so it can gate a CI/runbook step).
 *
 * <p>Usage: {@code java -jar reconciler.jar --gw <gw.jsonl> --wf <wf.jsonl> [--mapping <file.yml>] [--routes <routes.txt>]}
 *
 * <p>{@code --routes} names a file of canonical routes (one per line; {@code #} comments and blank lines ignored) that the exit gate MUST
 * see fully covered — a route listed there but absent from both logs fails the gate instead of passing unevaluated. The stimulus script
 * emits the routes file it covers, so the gate and stimulus share one source of truth.
 */
public final class Main {

    /** The outcome of one CLI invocation: the aggregate {@link Report} plus the process exit code derived from its exit gate. */
    public record Result(Report report, int exitCode) {
    }

    private Main() {}

    /** Runs the pipeline and returns the {@link Result} without touching stdout or the process exit status (testable in-process). */
    public static Result execute(String[] args) throws IOException {
        Map<String, String> parsedArgs = parseArgs(args);
        requireArg(parsedArgs, "--gw");
        requireArg(parsedArgs, "--wf");

        List<ShadowRecord> gw = RecordParser.parseLines(Files.lines(Path.of(parsedArgs.get("--gw"))));
        List<ShadowRecord> wf = RecordParser.parseLines(Files.lines(Path.of(parsedArgs.get("--wf"))));
        ReferenceMapping mapping =
            parsedArgs.containsKey("--mapping") ? ReferenceMapping.load(new FileInputStream(parsedArgs.get("--mapping")))
                : ReferenceMapping.load(Main.class.getResourceAsStream("/target-service-mapping.yml"));
        Set<String> expectedRoutes = parsedArgs.containsKey("--routes") ? readRoutes(parsedArgs.get("--routes")) : null;

        Report report = new Reconciler(mapping).run(gw, wf, expectedRoutes);
        return new Result(report, report.passesExitGate() ? 0 : 1);
    }

    /** Reads the {@code --routes} file: one canonical route per line, ignoring blank lines and {@code #} comments. */
    static Set<String> readRoutes(String path) throws IOException {
        try (var lines = Files.lines(Path.of(path))) {
            return lines.map(String::trim).filter(s -> !s.isEmpty() && !s.startsWith("#"))
                .collect(Collectors.toCollection(LinkedHashSet::new));
        }
    }

    public static void main(String[] args) throws IOException {
        Result result = execute(args);
        System.out.println(result.report().render());
        System.exit(result.exitCode());
    }

    private static Map<String, String> parseArgs(String[] args) {
        Map<String, String> map = new HashMap<>();
        for (int i = 0; i < args.length - 1; i += 2) {
            map.put(args[i], args[i + 1]);
        }
        return map;
    }

    private static void requireArg(Map<String, String> args, String name) {
        if (!args.containsKey(name)) {
            throw new IllegalArgumentException(
                "Missing required argument " + name
                    + ". Usage: --gw <gw.jsonl> --wf <wf.jsonl> [--mapping <file.yml>] [--routes <routes.txt>]"
            );
        }
    }
}
