package edu.harvard.hms.dbmi.avillach.mcp.codegen;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.harvard.hms.dbmi.avillach.mcp.query.QueryBinder;
import edu.harvard.hms.dbmi.avillach.mcp.query.QueryInput;
import edu.harvard.hms.dbmi.avillach.mcp.tool.ToolFailure;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Pins the Python generator's output byte for byte against golden files under {@code src/test/resources/codegen/python/}. Run with
 * {@code -Dcodegen.regenerate=true} to rewrite the golden files from the current generator, so any change to the output is deliberate and
 * shows up in review as a diff of those files.
 */
class PythonGeneratorTest {

    private static final Path GOLDEN = Path.of("src", "test", "resources", "codegen", "python");

    private static final boolean REGENERATE = Boolean.getBoolean("codegen.regenerate");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final AdapterSetup SETUP = new AdapterSetup("https://picsure.example.org", true, false, "3.0.0", "");

    private static final AdapterSetup GENOMIC_SETUP = new AdapterSetup("https://picsure.example.org", true, true, "3.0.0", "");

    private static final String EM_DASH = String.valueOf((char) 0x2014);

    /**
     * One golden case.
     *
     * @param name the golden file name without {@code .py}
     * @param query the query as the model would send it
     * @param kind the result type
     * @param setup the deployment setup
     */
    record Case(String name, String query, ResultKind kind, AdapterSetup setup) {

        @Override
        public String toString() {
            return name;
        }
    }

    static List<Case> cases() {
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
                ResultKind.timestamp, new AdapterSetup("https://aio.example.org/", false, false, "3.1.0", "")
            )
        );
    }

    private static String generate(Case c) throws IOException {
        AdapterCodeGenerator generator = new AdapterCodeGenerator(c.setup());
        return generator.generate(generator.walk(input(c.query()), c.kind()), Language.python).code();
    }

    private static QueryInput input(String json) throws IOException {
        return QueryBinder.bind(MAPPER.readValue(json, new TypeReference<Map<String, Object>>() {}), QueryInput.class);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    void matchesTheGoldenFile(Case c) throws IOException {
        String code = generate(c);
        Path golden = GOLDEN.resolve(c.name() + ".py");
        if (REGENERATE) {
            Files.createDirectories(GOLDEN);
            Files.writeString(golden, code, StandardCharsets.UTF_8);
        }
        assertThat(code).isEqualTo(Files.readString(golden, StandardCharsets.UTF_8));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    void holdsNoTokenNoDataFramePrintNoCommentAndNoPlatform(Case c) throws IOException {
        String code = generate(c);

        assertThat(code).endsWith("\n").contains("token=os.environ[\"PICSURE_TOKEN\"]").doesNotContain("Bearer", "Authorization")
            .doesNotContain("print(df", ".head(", "print(query", "Platform", EM_DASH, "--");
        assertThat(code.lines()).noneMatch(line -> line.strip().startsWith("#"));
        assertThat(code.chars()).allMatch(ch -> ch == '\n' || (ch >= 0x20 && ch < 0x7f));
        assertThat(generate(c)).isEqualTo(code);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    void pythonCompilesTheGoldenFile(Case c, @TempDir Path dir) throws Exception {
        assumeTrue(pythonAvailable(), "python3 is not installed");
        Path source = dir.resolve(c.name() + ".py");
        Files.copy(GOLDEN.resolve(c.name() + ".py"), source);

        Process process = new ProcessBuilder("python3", "-m", "py_compile", source.toString()).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

        assertThat(process.waitFor(60, TimeUnit.SECONDS)).isTrue();
        assertThat(process.exitValue()).as(output).isZero();
    }

    private static boolean pythonAvailable() {
        try {
            Process process = new ProcessBuilder("python3", "--version").redirectErrorStream(true).start();
            process.getInputStream().readAllBytes();
            return process.waitFor(30, TimeUnit.SECONDS) && process.exitValue() == 0;
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    @Test
    void theSetupNamesThePackageTheConfiguredVersionAndTheRuntime() throws IOException {
        AdapterCodeGenerator generator =
            new AdapterCodeGenerator(new AdapterSetup("https://picsure.example.org", true, false, "3.2.1", ""));
        GeneratedCode code = generator.generate(generator.walk(input(cases().get(0).query()), ResultKind.count), Language.python);

        assertThat(code.language()).isEqualTo(Language.python);
        assertThat(code.packageName()).isEqualTo("picsure");
        assertThat(code.minVersion()).isEqualTo("3.2.1");
        assertThat(code.runtime()).isEqualTo("Python >= 3.10");
        assertThat(code.check()).isEqualTo("python -c \"import importlib.metadata as m; print(m.version('picsure'))\"");
        assertThat(code.install()).isEqualTo("pip install 'picsure>=3.2.1'");
    }

    @Test
    void supportsGenomicIsWrittenOnlyWhenTheSiteHasIt() throws IOException {
        QueryInput query = input(cases().get(0).query());
        AdapterCodeGenerator off = new AdapterCodeGenerator(SETUP);
        AdapterCodeGenerator on = new AdapterCodeGenerator(GENOMIC_SETUP);

        assertThat(off.generate(off.walk(query, ResultKind.count), Language.python).code()).doesNotContain("supports_genomic");
        assertThat(on.generate(on.walk(query, ResultKind.count), Language.python).code()).contains(", supports_genomic=True)");
    }

    @Test
    void categoricalValuesAreSortedSoInputOrderDoesNotMatter() throws IOException {
        AdapterCodeGenerator generator = new AdapterCodeGenerator(SETUP);
        String first = generator.generate(
            generator.walk(
                input("""
                    {"phenotypicClause":{"phenotypicFilterType":"FILTER","conceptPath":"\\\\p\\\\x\\\\","values":["b","a","c"]}}"""),
                ResultKind.count
            ), Language.python
        ).code();
        String second = generator.generate(
            generator.walk(
                input("""
                    {"phenotypicClause":{"phenotypicFilterType":"FILTER","conceptPath":"\\\\p\\\\x\\\\","values":["c","b","a"]}}"""),
                ResultKind.count
            ), Language.python
        ).code();

        assertThat(first).isEqualTo(second).contains("categories=[\"a\", \"b\", \"c\"]");
    }

    @ParameterizedTest
    @EnumSource(value = Language.class, names = {"r", "bash"})
    void languagesNotYetWrittenAreAToolFailure(Language language) throws IOException {
        AdapterCodeGenerator generator = new AdapterCodeGenerator(SETUP);
        AdapterQuery query = generator.walk(input(cases().get(0).query()), ResultKind.count);

        assertThatThrownBy(() -> generator.generate(query, language)).isInstanceOf(ToolFailure.class)
            .hasMessage("Code in " + language.name() + " is not available yet. Use python.");
    }

    @Test
    void aGenomicMinOrMaxIsAToolFailureBecauseTheAdaptersCannotExpressIt() throws IOException {
        AdapterCodeGenerator generator = new AdapterCodeGenerator(SETUP);
        QueryInput query = input("""
            {"genomicFilters":[{"key":"Variant_frequency","min":0.1}]}""");

        assertThatThrownBy(() -> generator.walk(query, ResultKind.count)).isInstanceOf(ToolFailure.class)
            .hasMessage("Genomic filter 'Variant_frequency' has a min or max. The adapters take genomic values only.");
    }

    @Test
    void anEmptyQueryIsAToolFailure() throws IOException {
        AdapterCodeGenerator generator = new AdapterCodeGenerator(SETUP);

        assertThatThrownBy(() -> generator.walk(input("{\"select\":[\"\\\\p\\\\x\\\\\"]}"), ResultKind.count))
            .isInstanceOf(ToolFailure.class).hasMessageStartingWith("Adapter code needs a query with at least one filter");
        assertThat(generator.walk(input("{\"select\":[\"\\\\p\\\\x\\\\\"]}"), ResultKind.participant).includeConcepts())
            .containsExactly("\\p\\x\\");
    }

    @Test
    void anIncompleteFilterFailsTheSameWayAsInTheCountTools() throws IOException {
        AdapterCodeGenerator generator = new AdapterCodeGenerator(SETUP);

        assertThatThrownBy(() -> generator.walk(input("{\"phenotypicClause\":{\"conceptPath\":\"\\\\p\\\\\"}}"), ResultKind.count))
            .isInstanceOf(ToolFailure.class);
    }

    @Test
    void variableNamesComeFromTheLastSegmentAndAreDeduplicated() {
        VariableNames names = new VariableNames(java.util.Set.of("count"), "clause");

        assertThat(names.forConceptPath("\\phs1\\Demographics\\SEX\\")).isEqualTo("sex");
        assertThat(names.forConceptPath("\\phs2\\other\\sex\\")).isEqualTo("sex_2");
        assertThat(names.forConceptPath("\\phs3\\Count\\")).isEqualTo("count_2");
        assertThat(names.forConceptPath("\\phs3\\BMI (kg/m2)\\")).isEqualTo("bmi_kg_m2");
        assertThat(names.forConceptPath("\\phs3\\2nd visit\\")).isEqualTo("clause_2nd_visit");
        assertThat(names.forConceptPath("\\\\")).isEqualTo("clause");
        assertThat(names.forConceptPath("\\phs3\\\u00e9t\u00e9\\")).isEqualTo("t");
    }

    @Test
    void literalsAreAsciiPythonStrings() {
        assertThat(PythonGenerator.literal("\\a\\\"b\"")).isEqualTo("\"\\\\a\\\\\\\"b\\\"\"");
        assertThat(PythonGenerator.literal("caf\u00e9\t\u0001")).isEqualTo("\"caf\\u00e9\\t\\x01\"");
        assertThat(PythonGenerator.literal(new String(Character.toChars(0x1F600)))).isEqualTo("\"\\U0001f600\"");
        assertThat(PythonGenerator.number(40.0)).isEqualTo("40");
        assertThat(PythonGenerator.number(-0.25)).isEqualTo("-0.25");
        assertThat(PythonGenerator.number(1.0e20)).isEqualTo("1.0E20");
    }
}
