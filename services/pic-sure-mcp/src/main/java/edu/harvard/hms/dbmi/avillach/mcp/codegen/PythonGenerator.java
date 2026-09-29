package edu.harvard.hms.dbmi.avillach.mcp.codegen;

import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.PhenotypicFilterType;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Writes Python for the {@code picsure} adapter. The code connects by URL with the token from {@code PICSURE_TOKEN}, builds one variable
 * per clause with {@code buildClause}, {@code buildClauseGroup}, and {@code buildGenomicFilter}, assembles them with {@code buildQuery},
 * and runs the query. A count prints only the number, a cross count prints one number per concept path, and a participant or timestamp
 * result is written to {@code picsure_results/} with only its size and path printed. Strings are written as ASCII Python literals, so the
 * code holds no raw non-ASCII or control character.
 */
public final class PythonGenerator implements LanguageGenerator {

    /** The adapter's package name on PyPI. */
    public static final String PACKAGE = "picsure";

    /** The Python version the adapter needs. */
    public static final String RUNTIME = "Python >= 3.10";

    /** Prints the installed adapter version, or fails when the adapter is missing. */
    public static final String CHECK = "python -c \"import importlib.metadata as m; print(m.version('picsure'))\"";

    /** The directory participant and timestamp results are written to. */
    public static final String RESULTS_DIRECTORY = "picsure_results";

    private static final String INDENT = "    ";

    private static final Set<String> RESERVED = Set.of(
        "false", "none", "true", "and", "as", "assert", "async", "await", "break", "class", "continue", "def", "del", "elif", "else",
        "except", "finally", "for", "from", "global", "if", "import", "in", "is", "lambda", "nonlocal", "not", "or", "pass", "raise",
        "return", "try", "while", "with", "yield", "match", "case", "type", "print", "len", "os", "picsure", "session", "query", "count",
        "counts", "concept_path", "cell", "df", "output_path"
    );

    private static final Map<PhenotypicFilterType, String> FILTER_TYPES = Map.of(
        PhenotypicFilterType.FILTER, "FILTER", PhenotypicFilterType.REQUIRED, "REQUIRE", PhenotypicFilterType.ANY_RECORD_OF, "ANYRECORD"
    );

    private static final Map<String, String> GENOMIC_KEYS = Map.of(
        "Gene_with_variant", "GENE_WITH_VARIANT", "Variant_consequence_calculated", "VARIANT_CONSEQUENCE_CALCULATED",
        "Variant_frequency_as_text", "VARIANT_FREQUENCY_AS_TEXT", "Variant_class", "VARIANT_CLASS", "Variant_severity", "VARIANT_SEVERITY"
    );

    @Override
    public Language language() {
        return Language.python;
    }

    @Override
    public GeneratedCode generate(AdapterQuery query, AdapterSetup setup) {
        VariableNames names = new VariableNames(RESERVED, "clause");
        List<String> lines = new ArrayList<>();
        lines.add("import os");
        lines.add("import picsure");
        lines.add("");
        lines.add(connect(setup));
        lines.add("");
        String root = query.phenotypicClause() == null ? null : clause(query.phenotypicClause(), names, lines);
        List<String> genomic = new ArrayList<>();
        for (AdapterQuery.Genomic filter : query.genomicFilters()) {
            String name = names.next(filter.key());
            lines.add(name + " = picsure.buildGenomicFilter(" + genomicKey(filter.key()) + ", values=" + list(filter.values()) + ")");
            genomic.add(name);
        }
        if (root != null || !genomic.isEmpty()) {
            lines.add("");
        }
        lines.addAll(buildQuery(root, query.includeConcepts(), genomic));
        lines.add("");
        lines.addAll(run(query.resultKind()));
        String code = String.join("\n", lines) + "\n";
        String install = "pip install '" + PACKAGE + ">=" + setup.pythonMinVersion() + "'";
        return new GeneratedCode(Language.python, PACKAGE, setup.pythonMinVersion(), RUNTIME, CHECK, install, code);
    }

    private static String connect(AdapterSetup setup) {
        StringBuilder line = new StringBuilder("session = picsure.connect(").append(literal(setup.baseUrl()))
            .append(", token=os.environ[\"PICSURE_TOKEN\"], include_consents=").append(setup.includeConsents() ? "True" : "False");
        if (setup.supportsGenomic()) {
            line.append(", supports_genomic=True");
        }
        return line.append(")").toString();
    }

    private static String clause(AdapterQuery.Clause clause, VariableNames names, List<String> lines) {
        if (clause instanceof AdapterQuery.Filter filter) {
            String name = names.forConceptPath(filter.conceptPath());
            StringBuilder call = new StringBuilder(name).append(" = picsure.buildClause(").append(literal(filter.conceptPath()))
                .append(", type=picsure.PhenotypicFilterType.").append(FILTER_TYPES.get(filter.type()));
            if (!filter.categories().isEmpty()) {
                call.append(", categories=").append(list(filter.categories()));
            }
            if (filter.min() != null) {
                call.append(", min=").append(number(filter.min()));
            }
            if (filter.max() != null) {
                call.append(", max=").append(number(filter.max()));
            }
            lines.add(call.append(")").toString());
            return name;
        }
        AdapterQuery.Group group = (AdapterQuery.Group) clause;
        List<String> children = new ArrayList<>();
        for (AdapterQuery.Clause child : group.clauses()) {
            children.add(clause(child, names, lines));
        }
        String name = names.next("group");
        lines.add(
            name + " = picsure.buildClauseGroup([" + String.join(", ", children) + "], operator=picsure.GroupOperator."
                + group.operator().name() + ")"
        );
        return name;
    }

    private static List<String> buildQuery(String root, List<String> includeConcepts, List<String> genomic) {
        List<String> lines = new ArrayList<>();
        lines.add("query = picsure.buildQuery(");
        if (root != null) {
            lines.add(INDENT + "phenotypicFilter=" + root + ",");
        }
        if (!includeConcepts.isEmpty()) {
            lines.add(INDENT + "includeConcepts=[");
            includeConcepts.forEach(path -> lines.add(INDENT + INDENT + literal(path) + ","));
            lines.add(INDENT + "],");
        }
        if (!genomic.isEmpty()) {
            lines.add(INDENT + "genomicFilters=[" + String.join(", ", genomic) + "],");
        }
        lines.add(")");
        return lines;
    }

    private static List<String> run(ResultKind kind) {
        return switch (kind) {
            case count -> List.of("count = session.runQuery(query, type=\"count\")", "print(count.raw)");
            case cross_count -> List.of(
                "counts = session.runQuery(query, type=\"cross_count\")", "for concept_path, cell in counts.items():",
                INDENT + "print(concept_path, cell.raw)"
            );
            case participant, timestamp -> List.of(
                "os.makedirs(" + literal(RESULTS_DIRECTORY) + ", exist_ok=True)",
                "output_path = " + literal(RESULTS_DIRECTORY + "/" + kind.name() + ".csv"),
                "df = session.runQuery(query, type=" + literal(kind.name()) + ")", "session.exportCSV(df, output_path)",
                "print(f\"Saved {len(df)} rows and {len(df.columns)} columns to {output_path}\")"
            );
        };
    }

    private static String genomicKey(String key) {
        String member = GENOMIC_KEYS.get(key);
        return member == null ? literal(key) : "picsure.GenomicFilterKey." + member;
    }

    private static String list(List<String> values) {
        List<String> literals = values.stream().map(PythonGenerator::literal).toList();
        return "[" + String.join(", ", literals) + "]";
    }

    /**
     * Writes a number as a Python literal: a whole number without a fraction, anything else in Java's shortest round-trip form, which
     * Python reads back to the same value.
     *
     * @param value a finite number
     * @return the literal
     */
    static String number(double value) {
        if (value == Math.rint(value) && Math.abs(value) < 1e15) {
            return Long.toString((long) value);
        }
        return Double.toString(value);
    }

    /**
     * Writes a string as a double-quoted, ASCII-only Python literal. Backslashes and quotes are escaped, control characters become
     * {@code \xNN}, and anything outside printable ASCII becomes {@code \\uNNNN} or {@code \\UNNNNNNNN}.
     *
     * @param value the string
     * @return the literal
     */
    static String literal(String value) {
        StringBuilder out = new StringBuilder("\"");
        value.codePoints().forEach(cp -> {
            if (cp == '\\') {
                out.append("\\\\");
            } else if (cp == '"') {
                out.append("\\\"");
            } else if (cp == '\n') {
                out.append("\\n");
            } else if (cp == '\r') {
                out.append("\\r");
            } else if (cp == '\t') {
                out.append("\\t");
            } else if (cp < 0x20 || cp == 0x7f) {
                out.append(String.format("\\x%02x", cp));
            } else if (cp < 0x7f) {
                out.appendCodePoint(cp);
            } else if (cp <= 0xffff) {
                out.append(String.format("\\u%04x", cp));
            } else {
                out.append(String.format("\\U%08x", cp));
            }
        });
        return out.append('"').toString();
    }
}
