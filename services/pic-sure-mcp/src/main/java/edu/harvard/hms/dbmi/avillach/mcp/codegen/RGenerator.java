package edu.harvard.hms.dbmi.avillach.mcp.codegen;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Writes R for the {@code picsure} R adapter, which wraps the Python adapter through reticulate. The code connects by URL with the token
 * from {@code PICSURE_TOKEN}, builds one variable per clause with {@code picsure::buildClause}, {@code picsure::buildClauseGroup}, and
 * {@code picsure::buildGenomicFilter}, assembles them with {@code picsure::buildQuery}, and runs the query with {@code picsure::runQuery}.
 * A count prints only the number, a cross count prints one number per concept path, and a participant or timestamp result is written to
 * {@code picsure_results/} with {@code picsure::exportCSV} and only its size and path printed. Strings are written as ASCII R literals, so
 * the code holds no raw non-ASCII or control character.
 */
@Component
public final class RGenerator extends ScriptGenerator {

    /** The adapter's package name. */
    public static final String PACKAGE = "picsure";

    /** The GitHub repository the adapter installs from. */
    public static final String REPOSITORY = "hms-dbmi/pic-sure-r-adapter-hpds";

    /** What the adapter needs: R itself, reticulate, and the Python the adapter loads through it. */
    public static final String RUNTIME = "R >= 4.1, reticulate >= 1.41, and Python >= 3.10";

    /**
     * Prints the installed adapter's package version and, when it was installed from GitHub, the release tag it came from. The tag is the
     * value to compare with the required one, because a tagged release can carry a development package version.
     */
    public static final String CHECK =
        "Rscript -e 'cat(format(packageVersion(\"picsure\")), packageDescription(\"picsure\")$RemoteRef, fill = TRUE)'";

    /** The directory participant and timestamp results are written to. */
    public static final String RESULTS_DIRECTORY = "picsure_results";

    private static final String INDENT = "  ";

    private static final Set<String> RESERVED = Set.of(
        "if", "else", "repeat", "while", "function", "for", "next", "break", "in", "true", "false", "null", "inf", "nan", "na", "t", "f",
        "c", "list", "cat", "sprintf", "nrow", "ncol", "names", "library", "picsure", "session", "query", "count", "counts", "concept_path",
        "df", "output_path"
    );

    /** Creates the generator. */
    public RGenerator() {
        super(RESERVED);
    }

    @Override
    public Language language() {
        return Language.r;
    }

    @Override
    List<String> preamble() {
        return List.of("library(picsure)");
    }

    @Override
    GeneratedCode packaged(AdapterSetup setup, String code) {
        String install = "Rscript -e 'if (!requireNamespace(\"remotes\", quietly = TRUE)) install.packages(\"remotes\", "
            + "repos = \"https://cloud.r-project.org\"); " + "remotes::install_github(\"" + REPOSITORY + "@" + setup.rTag() + "\")'";
        return new GeneratedCode(Language.r, PACKAGE, setup.rTag(), RUNTIME, CHECK, install, code);
    }

    @Override
    String assign(String name, String expression) {
        return name + " <- " + expression;
    }

    @Override
    String call(String function, String... arguments) {
        return "picsure::" + function + "(" + String.join(", ", arguments) + ")";
    }

    @Override
    String keyword(String name, String value) {
        return name + " = " + value;
    }

    @Override
    String enumValue(String type, String member) {
        return "picsure::" + type + "$" + member;
    }

    @Override
    String strings(List<String> values) {
        return "c(" + String.join(", ", values.stream().map(this::string).toList()) + ")";
    }

    @Override
    String variables(List<String> names) {
        return "list(" + String.join(", ", names) + ")";
    }

    @Override
    String connect(AdapterSetup setup) {
        StringBuilder line = new StringBuilder("session <- picsure::connect(").append(string(setup.baseUrl()))
            .append(", token = Sys.getenv(\"PICSURE_TOKEN\"), include_consents = ").append(setup.includeConsents() ? "TRUE" : "FALSE");
        if (setup.supportsGenomic()) {
            line.append(", supports_genomic = TRUE");
        }
        return line.append(")").toString();
    }

    @Override
    List<String> buildQuery(String root, List<String> includeConcepts, List<String> genomic) {
        List<List<String>> arguments = new ArrayList<>();
        if (root != null) {
            arguments.add(List.of("phenotypicFilter = " + root));
        }
        if (!includeConcepts.isEmpty()) {
            List<String> block = new ArrayList<>();
            block.add("includeConcepts = c(");
            for (int i = 0; i < includeConcepts.size(); i++) {
                block.add(INDENT + string(includeConcepts.get(i)) + (i < includeConcepts.size() - 1 ? "," : ""));
            }
            block.add(")");
            arguments.add(block);
        }
        if (!genomic.isEmpty()) {
            arguments.add(List.of("genomicFilters = list(" + String.join(", ", genomic) + ")"));
        }
        List<String> lines = new ArrayList<>();
        lines.add("query <- picsure::buildQuery(");
        for (int i = 0; i < arguments.size(); i++) {
            List<String> block = arguments.get(i);
            for (int j = 0; j < block.size(); j++) {
                boolean lastLine = j == block.size() - 1;
                boolean more = i < arguments.size() - 1;
                lines.add(INDENT + block.get(j) + (lastLine && more ? "," : ""));
            }
        }
        lines.add(")");
        return lines;
    }

    @Override
    List<String> run(ResultKind kind) {
        return switch (kind) {
            case count -> List.of("count <- picsure::runQuery(session, query, type = \"count\")", "cat(count$raw, \"\\n\", sep = \"\")");
            case cross_count -> List.of(
                "counts <- picsure::runQuery(session, query, type = \"cross_count\")", "for (concept_path in names(counts)) {",
                INDENT + "cat(concept_path, \" \", counts[[concept_path]]$raw, \"\\n\", sep = \"\")", "}"
            );
            case participant, timestamp -> List.of(
                "dir.create(" + string(RESULTS_DIRECTORY) + ", showWarnings = FALSE, recursive = TRUE)",
                "output_path <- " + string(RESULTS_DIRECTORY + "/" + kind.name() + ".csv"),
                "df <- picsure::runQuery(session, query, type = " + string(kind.name()) + ")",
                "picsure::exportCSV(session, df, output_path)",
                "cat(sprintf(\"Saved %d rows and %d columns to %s\\n\", nrow(df), ncol(df), output_path))"
            );
        };
    }

    /**
     * Writes a code point beyond U+FFFF as {@code \\U{NNNNNNNN}}. Every other control character and everything outside printable ASCII
     * becomes {@code \\uNNNN}. R refuses a string that mixes {@code \\x} and {@code \\u} escapes, so {@code \\x} is never used. A NUL
     * character or an unpaired surrogate cannot appear in an R string, and neither reaches this class: the tools refuse both in every
     * string the query carries.
     */
    @Override
    String astral(int codePoint) {
        return String.format("\\U{%08x}", codePoint);
    }
}
