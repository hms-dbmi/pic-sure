package edu.harvard.hms.dbmi.avillach.mcp.codegen;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Pins the R generator's output byte for byte against golden files under {@code src/test/resources/codegen/r/}, for the same cases as the
 * Python generator. Run with {@code -Dcodegen.regenerate=true} to rewrite them. When {@code Rscript} is installed, each golden file must
 * parse, and its parse tree may call only the adapter functions and base helpers the generator uses, so no input can add a call.
 */
class RGeneratorTest {

    private static final Path GOLDEN = Path.of("src", "test", "resources", "codegen", "r");

    private static final String EM_DASH = String.valueOf((char) 0x2014);

    private static final Set<String> ALLOWED_CALLS = Set.of(
        "library", "picsure::connect", "Sys.getenv", "picsure::buildClause", "picsure::buildClauseGroup", "picsure::buildGenomicFilter",
        "picsure::buildQuery", "picsure::runQuery", "picsure::exportCSV", "list", "c", "cat", "names", "dir.create", "sprintf", "nrow",
        "ncol", "$", "::", "[[", "<-", "for", "{"
    );

    private static final String PARSE_CHECK = """
        exprs <- parse(file = commandArgs(trailingOnly = TRUE)[1], keep.source = FALSE)
        calls <- character()
        strings <- character()
        walk <- function(e) {
          if (is.call(e)) {
            calls <<- c(calls, paste(deparse(e[[1]]), collapse = ""))
            for (part in as.list(e)) walk(part)
          } else if (is.character(e)) {
            strings <<- c(strings, e)
          }
        }
        for (e in exprs) walk(e)
        cat("CALLS", unique(calls), sep = "\\n")
        cat("STRINGS", vapply(strings, function(s) paste(utf8ToInt(s), collapse = ","), ""), sep = "\\n")
        """;

    static List<CodegenCases.Case> cases() {
        return CodegenCases.cases();
    }

    private static String generate(CodegenCases.Case c) throws IOException {
        return CodegenCases.generate(c, Language.r).code();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    void matchesTheGoldenFile(CodegenCases.Case c) throws IOException {
        CodegenCases.assertGolden(GOLDEN.resolve(c.name() + ".R"), generate(c));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    void holdsNoTokenNoDataPrintNoCommentAndNoPlatform(CodegenCases.Case c) throws IOException {
        String code = generate(c);

        assertThat(code).endsWith("\n").contains("token = Sys.getenv(\"PICSURE_TOKEN\")").doesNotContain("Bearer", "Authorization")
            .doesNotContain("print(", "head(", "View(", "Platform", EM_DASH, "--", "\\x");
        assertThat(code.lines()).noneMatch(line -> line.strip().startsWith("#"));
        assertThat(code.chars()).allMatch(ch -> ch == '\n' || (ch >= 0x20 && ch < 0x7f));
        assertThat(generate(c)).isEqualTo(code);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    void rParsesTheGoldenFileAndCallsOnlyTheAdapter(CodegenCases.Case c, @TempDir Path dir) throws Exception {
        assumeTrue(CodegenCases.available("Rscript", "--version"), "Rscript is not installed");
        Path script = dir.resolve("check_parse.R");
        Files.writeString(script, PARSE_CHECK, StandardCharsets.UTF_8);

        CodegenCases.Outcome outcome =
            CodegenCases.run(null, Map.of(), "Rscript", script.toString(), GOLDEN.resolve(c.name() + ".R").toAbsolutePath().toString());

        assertThat(outcome.exitCode()).as(outcome.stderr()).isZero();
        List<String> lines = outcome.stdout().lines().toList();
        int strings = lines.indexOf("STRINGS");
        List<String> calls = lines.subList(lines.indexOf("CALLS") + 1, strings);
        assertThat(calls).isNotEmpty().isSubsetOf(ALLOWED_CALLS);
        if (c.name().equals("hostile")) {
            List<String> decoded = new ArrayList<>();
            for (String line : lines.subList(strings + 1, lines.size())) {
                int[] codePoints = line.isEmpty() ? new int[0] : Arrays.stream(line.split(",")).mapToInt(Integer::parseInt).toArray();
                decoded.add(new String(codePoints, 0, codePoints.length));
            }
            assertThat(decoded).contains(
                CodegenCases.HOSTILE_CATEGORY, CodegenCases.HOSTILE_SHELL_CATEGORY, CodegenCases.HOSTILE_SELECT,
                CodegenCases.HOSTILE_FILTER_PATH
            );
        }
    }

    @Test
    void theSetupNamesTheTaggedReleaseRAndReticulate() throws IOException {
        GeneratedCode code = CodegenCases.generate(CodegenCases.named("flat_filter"), Language.r);

        assertThat(code.language()).isEqualTo(Language.r);
        assertThat(code.packageName()).isEqualTo("picsure");
        assertThat(code.minVersion()).isEqualTo("v3.0.0");
        assertThat(code.runtime()).isEqualTo("R >= 4.1, reticulate >= 1.41, and Python >= 3.10");
        assertThat(code.check())
            .isEqualTo("Rscript -e 'cat(format(packageVersion(\"picsure\")), packageDescription(\"picsure\")$RemoteRef, fill = TRUE)'");
        assertThat(code.install()).isEqualTo("Rscript -e 'remotes::install_github(\"hms-dbmi/pic-sure-r-adapter-hpds@v3.0.0\")'");
    }

    @Test
    void theCheckCommandRunsWhereRIsInstalled() throws IOException {
        assumeTrue(CodegenCases.available("Rscript", "--version"), "Rscript is not installed");

        CodegenCases.Outcome outcome = CodegenCases.run(null, Map.of(), "bash", "-c", RGenerator.CHECK.replace("picsure", "stats"));

        assertThat(outcome.exitCode()).as(outcome.stderr()).isZero();
        assertThat(outcome.stdout()).matches("[0-9]+\\.[0-9]+\\.[0-9]+\n");
    }

    @Test
    void supportsGenomicIsWrittenOnlyWhenTheSiteHasIt() throws IOException {
        String off = CodegenCases.generate(CodegenCases.named("flat_filter"), Language.r).code();
        String on = CodegenCases.generate(CodegenCases.named("genomic_filters"), Language.r).code();

        assertThat(off).doesNotContain("supports_genomic").contains("include_consents = TRUE)");
        assertThat(on).contains(", supports_genomic = TRUE)");
    }

    @Test
    void literalsAreAsciiRStringsWithoutHexEscapes() {
        assertThat(RGenerator.literal("\\a\\\"b\"")).isEqualTo("\"\\\\a\\\\\\\"b\\\"\"");
        assertThat(RGenerator.literal("caf\u00e9\t\u0001\u007f\r\n")).isEqualTo("\"caf\\u00e9\\t\\u0001\\u007f\\r\\n\"");
        assertThat(RGenerator.literal(new String(Character.toChars(0x1F600)))).isEqualTo("\"\\U{0001f600}\"");
    }
}
