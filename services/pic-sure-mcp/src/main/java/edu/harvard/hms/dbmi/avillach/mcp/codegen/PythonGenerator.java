package edu.harvard.hms.dbmi.avillach.mcp.codegen;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Writes Python for the {@code picsure} adapter. The code connects by URL with the token from {@code PICSURE_TOKEN}, builds one variable
 * per clause with {@code buildClause}, {@code buildClauseGroup}, and {@code buildGenomicFilter}, assembles them with {@code buildQuery},
 * and runs the query. A count prints only the number, a cross count prints one number per concept path, and a participant or timestamp
 * result is written to {@code picsure_results/} with only its size and path printed. Strings are written as ASCII Python literals, so the
 * code holds no raw non-ASCII or control character.
 */
@Component
public final class PythonGenerator extends ScriptGenerator {

    /** The adapter's package name on PyPI. */
    public static final String PACKAGE = "picsure";

    /** The Python version the adapter needs. */
    public static final String RUNTIME = "Python >= 3.10";

    /** Prints the installed adapter version, or fails when the adapter is missing. */
    public static final String CHECK = "python3 -c \"import importlib.metadata as m; print(m.version('picsure'))\"";

    /** The directory participant and timestamp results are written to. */
    public static final String RESULTS_DIRECTORY = "picsure_results";

    private static final String INDENT = "    ";

    private static final Set<String> RESERVED = Set.of(
        "false", "none", "true", "and", "as", "assert", "async", "await", "break", "class", "continue", "def", "del", "elif", "else",
        "except", "finally", "for", "from", "global", "if", "import", "in", "is", "lambda", "nonlocal", "not", "or", "pass", "raise",
        "return", "try", "while", "with", "yield", "match", "case", "type", "print", "len", "os", "picsure", "session", "query", "count",
        "counts", "concept_path", "cell", "df", "output_path"
    );

    /** Creates the generator. */
    public PythonGenerator() {
        super(RESERVED);
    }

    @Override
    public Language language() {
        return Language.python;
    }

    @Override
    List<String> preamble() {
        return List.of("import os", "import picsure");
    }

    @Override
    GeneratedCode packaged(AdapterSetup setup, String code) {
        String install = "python3 -m pip install '" + PACKAGE + ">=" + setup.pythonMinVersion() + "'";
        return new GeneratedCode(Language.python, PACKAGE, setup.pythonMinVersion(), RUNTIME, CHECK, install, code);
    }

    @Override
    String assign(String name, String expression) {
        return name + " = " + expression;
    }

    @Override
    String call(String function, String... arguments) {
        return "picsure." + function + "(" + String.join(", ", arguments) + ")";
    }

    @Override
    String keyword(String name, String value) {
        return name + "=" + value;
    }

    @Override
    String enumValue(String type, String member) {
        return "picsure." + type + "." + member;
    }

    @Override
    String strings(List<String> values) {
        return variables(values.stream().map(this::string).toList());
    }

    @Override
    String variables(List<String> names) {
        return "[" + String.join(", ", names) + "]";
    }

    @Override
    String connect(AdapterSetup setup) {
        StringBuilder line = new StringBuilder("session = picsure.connect(").append(string(setup.baseUrl()))
            .append(", token=os.environ[\"PICSURE_TOKEN\"], include_consents=").append(setup.includeConsents() ? "True" : "False");
        if (setup.supportsGenomic()) {
            line.append(", supports_genomic=True");
        }
        return line.append(")").toString();
    }

    @Override
    List<String> buildQuery(String root, List<String> includeConcepts, List<String> genomic) {
        List<String> lines = new ArrayList<>();
        lines.add("query = picsure.buildQuery(");
        if (root != null) {
            lines.add(INDENT + "phenotypicFilter=" + root + ",");
        }
        if (!includeConcepts.isEmpty()) {
            lines.add(INDENT + "includeConcepts=[");
            includeConcepts.forEach(path -> lines.add(INDENT + INDENT + string(path) + ","));
            lines.add(INDENT + "],");
        }
        if (!genomic.isEmpty()) {
            lines.add(INDENT + "genomicFilters=[" + String.join(", ", genomic) + "],");
        }
        lines.add(")");
        return lines;
    }

    @Override
    List<String> run(ResultKind kind) {
        return switch (kind) {
            case count -> List.of("count = session.runQuery(query, type=\"count\")", "print(count.raw)");
            case cross_count -> List.of(
                "counts = session.runQuery(query, type=\"cross_count\")", "for concept_path, cell in counts.items():",
                INDENT + "print(concept_path, cell.raw)"
            );
            case participant, timestamp -> List.of(
                "os.makedirs(" + string(RESULTS_DIRECTORY) + ", exist_ok=True)",
                "output_path = " + string(RESULTS_DIRECTORY + "/" + kind.name() + ".csv"),
                "df = session.runQuery(query, type=" + string(kind.name()) + ")", "session.exportCSV(df, output_path)",
                "print(f\"Saved {len(df)} rows and {len(df.columns)} columns to {output_path}\")"
            );
        };
    }

    /**
     * Control characters become {@code \xNN}, and anything outside printable ASCII becomes {@code \\uNNNN} or {@code \\UNNNNNNNN}.
     */
    @Override
    String escape(int codePoint) {
        if (codePoint < 0x20 || codePoint == 0x7f) {
            return String.format("\\x%02x", codePoint);
        }
        return super.escape(codePoint);
    }

    @Override
    String astral(int codePoint) {
        return String.format("\\U%08x", codePoint);
    }
}
