package edu.harvard.hms.dbmi.avillach.mcp.codegen;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.harvard.hms.dbmi.avillach.mcp.query.QueryBinder;
import edu.harvard.hms.dbmi.avillach.mcp.query.QueryInput;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * The inputs every generator test renders, so the Python, R, and bash golden files describe the same queries, plus the helpers those tests
 * share: building a generator with all three languages, binding a query the way the tool does, comparing against a golden file, and running
 * an external checker.
 */
public final class CodegenCases {

    /** A site with consents on and genomic support off. */
    public static final AdapterSetup SETUP = new AdapterSetup("https://picsure.example.org", true, false, "3.0.0", "v3.0.0");

    /** A site with consents and genomic support on. */
    public static final AdapterSetup GENOMIC_SETUP = new AdapterSetup("https://picsure.example.org", true, true, "3.0.0", "v3.0.0");

    /** An all-in-one style site: a trailing slash on the URL, which {@link AdapterSetup} strips, consents off, other adapter versions. */
    public static final AdapterSetup AIO_SETUP = new AdapterSetup("https://aio.example.org/", false, false, "3.1.0", "v3.1.0");

    /** A category value that tries to end a Python, R, or bash string, or the bash heredoc, and run a command. */
    public static final String HOSTILE_CATEGORY = "q\"uote\\back\nnew\ttab\"]); __import__('os').system('x') #";

    /** A second category value with the R and bash payloads and the heredoc delimiter on a line of its own. */
    public static final String HOSTILE_SHELL_CATEGORY = "\"); system('x') #\nPICSURE_QUERY_JSON\n$(touch x) `touch x` '; touch x; '";

    /** A select path with the same payloads; concept paths refuse control characters, so it has no newline or tab. */
    public static final String HOSTILE_SELECT = "\\phs1\\a\"b\\\"]); __import__('os').system('x') #\\\"); system('x') # $(touch x)\\";

    /** A filter path with a quote and a backslash in its last segment. */
    public static final String HOSTILE_FILTER_PATH = "\\phs1\\site \"A\"\\";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private CodegenCases() {}

    /**
     * One golden case.
     *
     * @param name the golden file name without its extension
     * @param query the query as the model would send it
     * @param kind the result type
     * @param setup the deployment setup
     */
    public record Case(String name, String query, ResultKind kind, AdapterSetup setup) {

        @Override
        public String toString() {
            return name;
        }
    }

    /**
     * The shared cases: a flat filter, nested subqueries, genomic filters, a cross count, a participant result with escaping and a
     * duplicate select path, a timestamp result on an all-in-one setup, and a participant result with hostile strings.
     *
     * @return the cases
     */
    public static List<Case> cases() {
        return List.of(
            new Case("flat_filter", """
                {"phenotypicClause":{"phenotypicFilterType":"FILTER","conceptPath":"\\\\phs000007\\\\pht000009\\\\phv00000011\\\\SEX\\\\",
                "values":["Female"]}}""", ResultKind.count, SETUP), new Case("nested_subqueries", """
                {"phenotypicClause":{"operator":"AND","phenotypicClauses":[
                  {"phenotypicFilterType":"FILTER","conceptPath":"\\\\phs000001\\\\demographics\\\\sex\\\\","values":["Male","Female"]},
                  {"operator":"OR","phenotypicClauses":[
                    {"phenotypicFilterType":"FILTER","conceptPath":"\\\\phs000001\\\\demographics\\\\age\\\\","min":40,"max":65.5},
                    {"phenotypicFilterType":"REQUIRED","conceptPath":"\\\\phs000001\\\\exam\\\\BMI (kg/m2)\\\\"},
                    {"phenotypicFilterType":"ANY_RECORD_OF","conceptPath":"\\\\phs000002\\\\labs\\\\"}]},
                  {"phenotypicFilterType":"FILTER","conceptPath":"\\\\phs000002\\\\visit\\\\sex\\\\","values":["F"]},
                  {"phenotypicFilterType":"REQUIRED","conceptPath":"\\\\phs000003\\\\count\\\\"}]}}""", ResultKind.count, SETUP),
            new Case(
                "genomic_filters",
                """
                    {"phenotypicClause":{"phenotypicFilterType":"FILTER","conceptPath":"\\\\phs000001\\\\demographics\\\\sex\\\\",
                    "values":["Female"]},
                    "genomicFilters":[{"key":"Gene_with_variant","values":["APOE","BRCA1"]},{"key":"Variant_severity","values":["HIGH"]}]}""",
                ResultKind.count, GENOMIC_SETUP
            ),
            new Case(
                "cross_count",
                """
                    {"select":["\\\\phs000001\\\\demographics\\\\race\\\\"],
                    "phenotypicClause":{"phenotypicFilterType":"FILTER","conceptPath":"\\\\phs000001\\\\demographics\\\\age\\\\","min":18}}""",
                ResultKind.cross_count, SETUP
            ),
            new Case(
                "participant",
                """
                    {"select":["\\\\phs000001\\\\exam\\\\height\\\\","\\\\phs000001\\\\exam\\\\weight\\\\","\\\\phs000001\\\\exam\\\\height\\\\"],
                    "phenotypicClause":{"operator":"AND","phenotypicClauses":[
                      {"phenotypicFilterType":"FILTER","conceptPath":"\\\\phs000001\\\\site\\\\","values":["Caf\\u00e9 \\"North\\""]},
                      {"phenotypicFilterType":"FILTER","conceptPath":"\\\\phs000001\\\\demographics\\\\age\\\\","max":0.5}]}}""",
                ResultKind.participant, SETUP
            ),
            new Case(
                "timestamp", """
                    {"select":["\\\\phs000001\\\\visits\\\\date\\\\"],
                    "phenotypicClause":{"phenotypicFilterType":"ANY_RECORD_OF","conceptPath":"\\\\phs000001\\\\visits\\\\"}}""",
                ResultKind.timestamp, AIO_SETUP
            ), new Case("hostile", hostileQuery(), ResultKind.participant, SETUP)
        );
    }

    /**
     * The case with the given name.
     *
     * @param name the case name
     * @return the case
     */
    public static Case named(String name) {
        return cases().stream().filter(c -> c.name().equals(name)).findFirst().orElseThrow();
    }

    private static String hostileQuery() {
        Map<String, Object> filter = Map.of(
            "phenotypicFilterType", "FILTER", "conceptPath", HOSTILE_FILTER_PATH, "values",
            List.of(HOSTILE_CATEGORY, HOSTILE_SHELL_CATEGORY)
        );
        try {
            return MAPPER.writeValueAsString(Map.of("select", List.of(HOSTILE_SELECT), "phenotypicClause", filter));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * A generator with all three languages, wired the way Spring wires it.
     *
     * @param setup the deployment setup
     * @return the generator
     */
    public static AdapterCodeGenerator generator(AdapterSetup setup) {
        return new AdapterCodeGenerator(setup, List.of(new PythonGenerator(), new RGenerator(), new BashGenerator()));
    }

    /**
     * Binds a query the way the tool binds it.
     *
     * @param json the query as JSON
     * @return the bound query
     * @throws IOException if the JSON does not parse
     */
    public static QueryInput input(String json) throws IOException {
        return QueryBinder.bind(MAPPER.readValue(json, new TypeReference<Map<String, Object>>() {}), QueryInput.class);
    }

    /**
     * Generates the full result for a case in one language.
     *
     * @param c the case
     * @param language the language
     * @return the generated code and setup
     * @throws IOException if the case's JSON does not parse
     */
    public static GeneratedCode generate(Case c, Language language) throws IOException {
        AdapterCodeGenerator generator = generator(c.setup());
        return generator.generate(generator.walk(input(c.query()), c.kind()), language);
    }

    /**
     * Compares code with its golden file byte for byte, first rewriting the file when {@code -Dcodegen.regenerate=true} is set.
     *
     * @param golden the golden file
     * @param code the generated code
     * @throws IOException if the file cannot be read or written
     */
    public static void assertGolden(Path golden, String code) throws IOException {
        if (Boolean.getBoolean("codegen.regenerate")) {
            Files.createDirectories(golden.getParent());
            Files.writeString(golden, code, StandardCharsets.UTF_8);
        }
        assertThat(code).isEqualTo(Files.readString(golden, StandardCharsets.UTF_8));
    }

    /**
     * Whether a command runs and exits with status zero, used to skip a check whose tool is not installed.
     *
     * @param command the command and its arguments
     * @return true when the command ran and succeeded
     */
    public static boolean available(String... command) {
        try {
            return run(null, Map.of(), command).exitCode() == 0;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * The outcome of an external command.
     *
     * @param exitCode the exit status
     * @param stdout what it wrote to standard output
     * @param stderr what it wrote to standard error
     */
    public record Outcome(int exitCode, String stdout, String stderr) {
    }

    /**
     * Runs an external command to completion, with a one-minute limit.
     *
     * @param directory the working directory, or null for the current one
     * @param environment variables to add to the inherited environment
     * @param command the command and its arguments
     * @return its exit status and output
     * @throws IOException if the command cannot be started or does not finish in time
     */
    public static Outcome run(Path directory, Map<String, String> environment, String... command) throws IOException {
        ProcessBuilder builder = new ProcessBuilder(command);
        if (directory != null) {
            builder.directory(directory.toFile());
        }
        builder.environment().putAll(environment);
        Path stdout = Files.createTempFile("codegen-out", ".txt");
        Path stderr = Files.createTempFile("codegen-err", ".txt");
        try {
            Process process = builder.redirectOutput(stdout.toFile()).redirectError(stderr.toFile()).start();
            if (!process.waitFor(60, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IOException("Timed out: " + String.join(" ", command));
            }
            return new Outcome(
                process.exitValue(), Files.readString(stdout, StandardCharsets.UTF_8), Files.readString(stderr, StandardCharsets.UTF_8)
            );
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException(e);
        } finally {
            Files.deleteIfExists(stdout);
            Files.deleteIfExists(stderr);
        }
    }
}
