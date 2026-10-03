package edu.harvard.hms.dbmi.avillach.mcp.codegen;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pins the bash generator's output byte for byte against golden files under {@code src/test/resources/codegen/bash/}, for the same cases as
 * the Python and R generators. Run with {@code -Dcodegen.regenerate=true} to rewrite them. Each golden file must pass {@code bash -n}, and
 * {@code shellcheck} when it is installed. The scripts are also run against a stand-in {@code curl} that records what it was given, to show
 * the request body is the exact query, the token travels only in a header file, and only counts or a line count and path are printed.
 */
class BashGeneratorTest {

    private static final Path GOLDEN = Path.of("src", "test", "resources", "codegen", "bash");

    private static final String EM_DASH = String.valueOf((char) 0x2014);

    private static final String TOKEN = "SECRET-TOKEN-4f7a";

    private static final String QUERY_ID = "3fa85f64-5717-4562-b3fc-2c963f66afa6";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final Pattern HEREDOC = Pattern.compile("<<'PICSURE_QUERY_JSON'\\n(.*?)\\nPICSURE_QUERY_JSON\\n", Pattern.DOTALL);

    private static final String FAKE_CURL = """
        #!/usr/bin/env bash
        set -euo pipefail
        log="$FAKE_CURL_LOG"
        printf '%s\\n' "$@" >> "$log/argv"
        output=""
        url=""
        while (( $# > 0 )); do
          case "$1" in
            -H)
              if [[ "$2" == @* ]]; then cat "${2#@}" >> "$log/headers"; else printf '%s\\n' "$2" >> "$log/headers"; fi
              shift 2
              ;;
            --data-binary)
              cp "${2#@}" "$log/body.json"
              shift 2
              ;;
            --output)
              output="$2"
              shift 2
              ;;
            -*)
              shift
              ;;
            *)
              url="$1"
              shift
              ;;
          esac
        done
        printf '%s\\n' "$url" >> "$log/urls"
        case "$url" in
          */query/sync) printf '%s' "$FAKE_SYNC_RESPONSE" ;;
          */status)
            checks=$(( $(cat "$log/checks" 2>/dev/null || echo 0) + 1 ))
            echo "$checks" > "$log/checks"
            if (( checks < FAKE_READY_AFTER )); then printf '{"status":"PENDING"}'; else printf '{"status":"%s"}' "$FAKE_FINAL_STATUS"; fi
            ;;
          */result) printf 'patient_id,sex\\n1,F\\n2,M\\n' > "$output" ;;
          */query) printf '{"picsureResultId":"%s"}' "$FAKE_QUERY_ID" ;;
        esac
        """;

    static List<CodegenCases.Case> cases() {
        return CodegenCases.cases();
    }

    private static String generate(CodegenCases.Case c) throws IOException {
        return CodegenCases.generate(c, Language.bash).code();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    void matchesTheGoldenFile(CodegenCases.Case c) throws IOException {
        CodegenCases.assertGolden(GOLDEN.resolve(c.name() + ".sh"), generate(c));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    void holdsNoTokenNoDataPrintAndNoCommentBeyondTheShebang(CodegenCases.Case c) throws IOException {
        String code = generate(c);

        assertThat(code).startsWith("#!/usr/bin/env bash\nset -euo pipefail\n").endsWith("\n").doesNotContain(EM_DASH, "Platform")
            .doesNotContain("cat \"$output_path\"", "head ", "set -x");
        assertThat(
            code.replace("--silent", "").replace("--show-error", "").replace("--fail", "").replace("--data-binary", "")
                .replace("--output", "")
        ).doesNotContain("--");
        assertThat(code.lines().skip(1)).noneMatch(line -> line.strip().startsWith("#"));
        assertThat(code.chars()).allMatch(ch -> ch == '\n' || (ch >= 0x20 && ch < 0x7f));
        Matcher bearer = Pattern.compile("Bearer (\\S*)").matcher(code);
        assertThat(bearer.find()).isTrue();
        assertThat(bearer.group(1)).isEqualTo("%s\\n'");
        assertThat(bearer.find()).isFalse();
        assertThat(code).containsOnlyOnce("Authorization").contains("-H @<(printf 'Authorization: Bearer %s\\n' \"$PICSURE_TOKEN\")");
        assertThat(generate(c)).isEqualTo(code);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    void theHeredocHoldsTheExactQueryEnvelope(CodegenCases.Case c) throws IOException {
        String code = generate(c);
        Matcher heredoc = HEREDOC.matcher(code);

        assertThat(heredoc.find()).isTrue();
        JsonNode envelope = MAPPER.readTree(heredoc.group(1));
        assertThat(envelope.properties()).hasSize(1);
        JsonNode query = envelope.get("query");
        assertThat(query.get("expectedResultType").asText()).isEqualTo(c.kind().resultType().name());
        assertThat(query.has("resourceUUID")).isFalse();
        assertThat(query.has("id")).isFalse();
        assertThat(query.has("picsureId")).isFalse();
        assertThat(heredoc.find()).isFalse();
        assertThat(code).containsOnlyOnce("PICSURE_QUERY_JSON\n");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    void bashParsesTheGoldenFile(CodegenCases.Case c) throws IOException {
        assumeTrue(CodegenCases.available("bash", "--version"), "bash is not installed");

        CodegenCases.Outcome outcome = CodegenCases.run(null, Map.of(), "bash", "-n", GOLDEN.resolve(c.name() + ".sh").toString());

        assertThat(outcome.exitCode()).as(outcome.stderr()).isZero();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    void shellcheckPassesTheGoldenFile(CodegenCases.Case c) throws IOException {
        assumeTrue(CodegenCases.available("shellcheck", "--version"), "shellcheck is not installed");

        CodegenCases.Outcome outcome = CodegenCases.run(null, Map.of(), "shellcheck", GOLDEN.resolve(c.name() + ".sh").toString());

        assertThat(outcome.exitCode()).as(outcome.stdout()).isZero();
    }

    @Test
    void theHostileScriptDiffersFromAnOrdinaryOneOnlyInsideTheHeredoc() throws IOException {
        String hostile = generate(CodegenCases.named("hostile"));
        String participant = generate(CodegenCases.named("participant"));

        assertThat(withoutHeredocBody(hostile)).isEqualTo(withoutHeredocBody(participant));
        JsonNode filter = MAPPER.readTree(heredocBody(hostile)).get("query").get("phenotypicClause");
        assertThat(filter.get("values").get(0).asText()).isEqualTo(CodegenCases.HOSTILE_SHELL_CATEGORY);
        assertThat(filter.get("values").get(1).asText()).isEqualTo(CodegenCases.HOSTILE_CATEGORY);
        assertThat(filter.get("conceptPath").asText()).isEqualTo(CodegenCases.HOSTILE_FILTER_PATH);
        assertThat(MAPPER.readTree(heredocBody(hostile)).get("query").get("select").get(0).asText()).isEqualTo(CodegenCases.HOSTILE_SELECT);
    }

    @Test
    void aJsonLineCanNeverEqualTheHeredocDelimiter() throws IOException {
        AdapterCodeGenerator generator = CodegenCases.generator(CodegenCases.SETUP);
        String json = "{\"phenotypicClause\":{\"phenotypicFilterType\":\"FILTER\",\"conceptPath\":\"\\\\p\\\\\","
            + "\"values\":[\"\\nPICSURE_QUERY_JSON\\n\",\"PICSURE_QUERY_JSON\"]}}";
        AdapterQuery query = generator.walk(CodegenCases.input(json), ResultKind.count);

        List<String> lines = BashGenerator.json(query);

        assertThat(lines).doesNotContain(BashGenerator.HEREDOC_DELIMITER).allMatch(line -> line.startsWith(" ") || line.matches("[{}]"));
        assertThat(String.join("\n", lines)).contains("\"\\nPICSURE_QUERY_JSON\\n\"");
    }

    @Test
    void zeroBoundsAreKeptAndEmptyFieldsLeftOut() throws IOException {
        AdapterCodeGenerator generator = CodegenCases.generator(CodegenCases.SETUP);
        AdapterQuery query = generator.walk(CodegenCases.input("""
            {"phenotypicClause":{"phenotypicFilterType":"FILTER","conceptPath":"\\\\p\\\\","min":0}}"""), ResultKind.count);

        JsonNode filter = MAPPER.readTree(String.join("\n", BashGenerator.json(query))).get("query").get("phenotypicClause");

        assertThat(filter.get("min").asDouble()).isZero();
        assertThat(filter.has("values")).isFalse();
        assertThat(filter.has("max")).isFalse();
        assertThat(filter.has("not")).isFalse();
    }

    @Test
    void quotedWordsReadBackAsTheOriginalBytes() throws IOException {
        assumeTrue(CodegenCases.available("bash", "--version"), "bash is not installed");
        String value = "https://h/it's caf" + (char) 0xe9 + " $(touch x) `id` \\ \" \t";

        CodegenCases.Outcome outcome = CodegenCases.run(null, Map.of(), "bash", "-c", "printf %s " + BashGenerator.quote(value));

        assertThat(outcome.stdout()).isEqualTo(value);
    }

    @Test
    void theBaseUrlIsQuotedAndItsTrailingSlashDropped() {
        assertThat(BashGenerator.quote("https://h/it's")).isEqualTo("'https://h/it'\\''s'");
        assertThat(BashGenerator.quote("caf" + (char) 0xe9)).isEqualTo("'caf'$'\\xc3'''$'\\xa9'''");
        assertThat(generateQuietly(CodegenCases.named("timestamp")))
            .contains("query_url='https://aio.example.org/picsure/hpds/auth/query'\n");
    }

    @Test
    void theSetupNamesCurlAndJqWithNoVersion() throws IOException {
        GeneratedCode code = CodegenCases.generate(CodegenCases.named("flat_filter"), Language.bash);

        assertThat(code.packageName()).isEqualTo("curl and jq");
        assertThat(code.minVersion()).isNull();
        assertThat(code.runtime()).isEqualTo("bash");
        assertThat(code.check()).isEqualTo("curl --version | head -n 1 && jq --version");
        assertThat(code.install()).startsWith("Install curl and jq with the system package manager");
    }

    @Test
    void aCountScriptSendsTheQueryAndPrintsOnlyTheCount(@TempDir Path dir) throws IOException {
        assumeTrue(CodegenCases.available("jq", "--version"), "jq is not installed");
        Path log = Files.createDirectories(dir.resolve("log"));

        CodegenCases.Outcome outcome = runWithFakeCurl(dir, "flat_filter", Map.of("FAKE_SYNC_RESPONSE", "4217"));

        assertThat(outcome.exitCode()).as(outcome.stderr()).isZero();
        assertThat(outcome.stdout()).isEqualTo("4217\n");
        assertThat(Files.readString(log.resolve("urls"))).isEqualTo("https://picsure.example.org/picsure/hpds/auth/query/sync\n");
        assertThat(MAPPER.readTree(log.resolve("body.json").toFile()))
            .isEqualTo(MAPPER.readTree(heredocBody(generateQuietly(CodegenCases.named("flat_filter")))));
        assertTokenOnlyInTheHeaderFile(log);
    }

    @Test
    void aCrossCountScriptPrintsOneLinePerConceptPath(@TempDir Path dir) throws IOException {
        assumeTrue(CodegenCases.available("jq", "--version"), "jq is not installed");

        CodegenCases.Outcome outcome =
            runWithFakeCurl(dir, "cross_count", Map.of("FAKE_SYNC_RESPONSE", "{\"\\\\p\\\\race\\\\\":\"12\",\"\\\\p\\\\age\\\\\":7}"));

        assertThat(outcome.exitCode()).as(outcome.stderr()).isZero();
        assertThat(outcome.stdout()).isEqualTo("\\p\\race\\ 12\n\\p\\age\\ 7\n");
    }

    @Test
    void aRowScriptPollsDownloadsAndPrintsOnlyTheLineCountAndPath(@TempDir Path dir) throws IOException {
        assumeTrue(CodegenCases.available("jq", "--version"), "jq is not installed");
        Path log = Files.createDirectories(dir.resolve("log"));

        CodegenCases.Outcome outcome = runWithFakeCurl(dir, "hostile", Map.of("FAKE_READY_AFTER", "2"));

        assertThat(outcome.exitCode()).as(outcome.stderr()).isZero();
        assertThat(outcome.stdout()).isEqualTo("Saved 3 lines to picsure_results/participant.csv\n");
        assertThat(Files.readString(dir.resolve("work/picsure_results/participant.csv"))).isEqualTo("patient_id,sex\n1,F\n2,M\n");
        String base = "https://picsure.example.org/picsure/hpds/auth/query";
        assertThat(Files.readString(log.resolve("urls"))).isEqualTo(
            base + "\n" + base + "/" + QUERY_ID + "/status\n" + base + "/" + QUERY_ID + "/status\n" + base + "/" + QUERY_ID + "/result\n"
        );
        JsonNode filter = MAPPER.readTree(log.resolve("body.json").toFile()).get("query").get("phenotypicClause");
        assertThat(filter.get("values").get(1).asText()).isEqualTo(CodegenCases.HOSTILE_CATEGORY);
        try (var files = Files.list(dir.resolve("work"))) {
            assertThat(files.map(p -> p.getFileName().toString())).containsExactly("picsure_results");
        }
        assertTokenOnlyInTheHeaderFile(log);
    }

    @Test
    void aRowScriptStopsOnAnErrorStatusOrABadQueryId(@TempDir Path dir) throws IOException {
        assumeTrue(CodegenCases.available("jq", "--version"), "jq is not installed");

        CodegenCases.Outcome error = runWithFakeCurl(dir.resolve("a"), "participant", Map.of("FAKE_FINAL_STATUS", "ERROR"));
        CodegenCases.Outcome badId = runWithFakeCurl(dir.resolve("b"), "participant", Map.of("FAKE_QUERY_ID", "../../admin"));

        assertThat(error.exitCode()).isEqualTo(1);
        assertThat(error.stderr()).contains("PIC-SURE reported an error for query " + QUERY_ID + ".");
        assertThat(error.stdout()).isEmpty();
        assertThat(badId.exitCode()).isEqualTo(1);
        assertThat(badId.stderr()).contains("PIC-SURE did not return a query id.");
        assertThat(Files.readString(dir.resolve("b/log/urls"))).doesNotContain("admin");
    }

    @Test
    void aScriptWithoutATokenStopsBeforeAnyRequest(@TempDir Path dir) throws IOException {
        assumeTrue(CodegenCases.available("jq", "--version"), "jq is not installed");

        CodegenCases.Outcome outcome = runWithFakeCurl(dir, "flat_filter", Map.of("PICSURE_TOKEN", ""));

        assertThat(outcome.exitCode()).isEqualTo(1);
        assertThat(outcome.stderr()).contains("Set PICSURE_TOKEN to your PIC-SURE token before running this script.");
        assertThat(dir.resolve("log/argv")).doesNotExist();
    }

    private static CodegenCases.Outcome runWithFakeCurl(Path dir, String caseName, Map<String, String> overrides) throws IOException {
        Path bin = Files.createDirectories(dir.resolve("bin"));
        Path log = Files.createDirectories(dir.resolve("log"));
        Path work = Files.createDirectories(dir.resolve("work"));
        Path curl = bin.resolve("curl");
        Files.writeString(curl, FAKE_CURL, StandardCharsets.UTF_8);
        Files.setPosixFilePermissions(curl, PosixFilePermissions.fromString("rwxr-xr-x"));
        Path script = dir.resolve(caseName + ".sh");
        Files.copy(GOLDEN.resolve(caseName + ".sh"), script);
        Map<String, String> environment = new java.util.HashMap<>(
            Map.of(
                "PATH", bin + ":" + System.getenv("PATH"), "PICSURE_TOKEN", TOKEN, "FAKE_CURL_LOG", log.toString(), "FAKE_READY_AFTER", "1",
                "FAKE_FINAL_STATUS", "AVAILABLE", "FAKE_QUERY_ID", QUERY_ID, "FAKE_SYNC_RESPONSE", "0"
            )
        );
        environment.putAll(overrides);
        return CodegenCases.run(work, environment, "bash", script.toString());
    }

    private static void assertTokenOnlyInTheHeaderFile(Path log) throws IOException {
        assertThat(Files.readString(log.resolve("argv"))).doesNotContain(TOKEN);
        assertThat(Files.readString(log.resolve("headers"))).contains("Authorization: Bearer " + TOKEN + "\n");
    }

    private static String generateQuietly(CodegenCases.Case c) {
        try {
            return generate(c);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String heredocBody(String code) {
        Matcher heredoc = HEREDOC.matcher(code);
        assertThat(heredoc.find()).isTrue();
        return heredoc.group(1);
    }

    private static String withoutHeredocBody(String code) {
        return HEREDOC.matcher(code).replaceFirst("<<'PICSURE_QUERY_JSON'\nPICSURE_QUERY_JSON\n");
    }
}
