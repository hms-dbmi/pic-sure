package edu.harvard.hms.dbmi.avillach.mcp.codegen;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.harvard.hms.dbmi.avillach.mcp.query.QueryInput;
import edu.harvard.hms.dbmi.avillach.mcp.tool.ToolFailure;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Pins the Python generator's output byte for byte against golden files under {@code src/test/resources/codegen/python/}. Run with
 * {@code -Dcodegen.regenerate=true} to rewrite the golden files from the current generator, so any change to the output is deliberate and
 * shows up in review as a diff of those files.
 */
class PythonGeneratorTest {

    private static final Path GOLDEN = Path.of("src", "test", "resources", "codegen", "python");

    private static final AdapterSetup SETUP = CodegenCases.SETUP;

    private static final AdapterSetup GENOMIC_SETUP = CodegenCases.GENOMIC_SETUP;

    private static final String EM_DASH = String.valueOf((char) 0x2014);

    private static final Set<String> ALLOWED_CALLS = Set.of(
        "picsure.connect", "picsure.buildClause", "picsure.buildClauseGroup", "picsure.buildGenomicFilter", "picsure.buildQuery",
        "session.runQuery", "session.exportCSV", "os.makedirs", "print", "len", "counts.items"
    );

    private static final String AST_CHECK = """
        import ast, json, sys
        tree = ast.parse(open(sys.argv[1], encoding="ascii").read())
        statements = sorted({type(node).__name__ for node in tree.body})
        calls = sorted({ast.unparse(node.func) for node in ast.walk(tree) if isinstance(node, ast.Call)})
        names = sorted({node.id for node in ast.walk(tree) if isinstance(node, ast.Name)})
        strings = [node.value for node in ast.walk(tree) if isinstance(node, ast.Constant) and isinstance(node.value, str)]
        print(json.dumps({"statements": statements, "calls": calls, "names": names, "strings": strings}))
        """;

    static List<CodegenCases.Case> cases() {
        return CodegenCases.cases();
    }

    private static String generate(CodegenCases.Case c) throws IOException {
        return CodegenCases.generate(c, Language.python).code();
    }

    private static QueryInput input(String json) throws IOException {
        return CodegenCases.input(json);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    void matchesTheGoldenFile(CodegenCases.Case c) throws IOException {
        CodegenCases.assertGolden(GOLDEN.resolve(c.name() + ".py"), generate(c));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    void holdsNoTokenNoDataFramePrintNoCommentAndNoPlatform(CodegenCases.Case c) throws IOException {
        String code = generate(c);

        assertThat(code).endsWith("\n").contains("token=os.environ[\"PICSURE_TOKEN\"]").doesNotContain("Bearer", "Authorization")
            .doesNotContain("print(df", ".head(", "print(query", "Platform", EM_DASH, "--");
        assertThat(code.lines()).noneMatch(line -> line.strip().startsWith("#"));
        assertThat(code.chars()).allMatch(ch -> ch == '\n' || (ch >= 0x20 && ch < 0x7f));
        assertThat(generate(c)).isEqualTo(code);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    void pythonCompilesTheGoldenFile(CodegenCases.Case c, @TempDir Path dir) throws Exception {
        assumeTrue(CodegenCases.available("python3", "--version"), "python3 is not installed");
        Path source = dir.resolve(c.name() + ".py");
        Files.copy(GOLDEN.resolve(c.name() + ".py"), source);

        CodegenCases.Outcome outcome = CodegenCases.run(null, Map.of(), "python3", "-m", "py_compile", source.toString());

        assertThat(outcome.exitCode()).as(outcome.stderr()).isZero();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    void theSyntaxTreeHoldsOnlyTheExpectedStatementsAndCalls(CodegenCases.Case c, @TempDir Path dir) throws Exception {
        assumeTrue(CodegenCases.available("python3", "--version"), "python3 is not installed");
        Path script = dir.resolve("check_ast.py");
        Files.writeString(script, AST_CHECK, StandardCharsets.UTF_8);

        CodegenCases.Outcome outcome =
            CodegenCases.run(null, Map.of(), "python3", script.toString(), GOLDEN.resolve(c.name() + ".py").toAbsolutePath().toString());

        assertThat(outcome.exitCode()).as(outcome.stderr()).isZero();
        Map<String, List<String>> tree = new ObjectMapper().readValue(outcome.stdout(), new TypeReference<>() {});
        assertThat(tree.get("statements")).isSubsetOf("Import", "Assign", "Expr", "For");
        assertThat(tree.get("calls")).isSubsetOf(ALLOWED_CALLS);
        assertThat(tree.get("names")).noneMatch(name -> name.startsWith("__"));
        if (c.name().equals("hostile")) {
            assertThat(tree.get("strings")).contains(
                CodegenCases.HOSTILE_CATEGORY, CodegenCases.HOSTILE_SHELL_CATEGORY, CodegenCases.HOSTILE_SELECT,
                CodegenCases.HOSTILE_FILTER_PATH
            );
        }
    }

    @Test
    void theSetupNamesThePackageTheConfiguredVersionAndTheRuntime() throws IOException {
        AdapterCodeGenerator generator = CodegenCases.generator(new AdapterSetup("https://picsure.example.org", true, false, "3.2.1", ""));
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
        AdapterCodeGenerator off = CodegenCases.generator(SETUP);
        AdapterCodeGenerator on = CodegenCases.generator(GENOMIC_SETUP);

        assertThat(off.generate(off.walk(query, ResultKind.count), Language.python).code()).doesNotContain("supports_genomic");
        assertThat(on.generate(on.walk(query, ResultKind.count), Language.python).code()).contains(", supports_genomic=True)");
    }

    @Test
    void categoricalValuesAreSortedSoInputOrderDoesNotMatter() throws IOException {
        AdapterCodeGenerator generator = CodegenCases.generator(SETUP);
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

    @Test
    void aGenomicMinOrMaxIsAToolFailureBecauseTheAdaptersCannotExpressIt() throws IOException {
        AdapterCodeGenerator generator = CodegenCases.generator(SETUP);
        QueryInput query = input("""
            {"genomicFilters":[{"key":"Variant_frequency","min":0.1}]}""");

        assertThatThrownBy(() -> generator.walk(query, ResultKind.count)).isInstanceOf(ToolFailure.class)
            .hasMessage("Genomic filter 'Variant_frequency' has a min or max. The adapters take genomic values only.");
    }

    @Test
    void anEmptyQueryIsAToolFailure() throws IOException {
        AdapterCodeGenerator generator = CodegenCases.generator(SETUP);

        assertThatThrownBy(() -> generator.walk(input("{\"select\":[\"\\\\p\\\\x\\\\\"]}"), ResultKind.count))
            .isInstanceOf(ToolFailure.class).hasMessageStartingWith("Adapter code needs a query with at least one filter");
        assertThat(generator.walk(input("{\"select\":[\"\\\\p\\\\x\\\\\"]}"), ResultKind.participant).includeConcepts())
            .containsExactly("\\p\\x\\");
    }

    @Test
    void anIncompleteFilterFailsTheSameWayAsInTheCountTools() throws IOException {
        AdapterCodeGenerator generator = CodegenCases.generator(SETUP);

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
