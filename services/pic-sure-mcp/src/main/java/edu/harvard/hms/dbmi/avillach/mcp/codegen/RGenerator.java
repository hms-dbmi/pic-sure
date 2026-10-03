package edu.harvard.hms.dbmi.avillach.mcp.codegen;

import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.PhenotypicFilterType;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
public final class RGenerator implements LanguageGenerator {

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

    private static final Map<PhenotypicFilterType, String> FILTER_TYPES = Map.of(
        PhenotypicFilterType.FILTER, "FILTER", PhenotypicFilterType.REQUIRED, "REQUIRE", PhenotypicFilterType.ANY_RECORD_OF, "ANYRECORD"
    );

    private static final Map<String, String> GENOMIC_KEYS = Map.of(
        "Gene_with_variant", "GENE_WITH_VARIANT", "Variant_consequence_calculated", "VARIANT_CONSEQUENCE_CALCULATED",
        "Variant_frequency_as_text", "VARIANT_FREQUENCY_AS_TEXT", "Variant_class", "VARIANT_CLASS", "Variant_severity", "VARIANT_SEVERITY"
    );

    @Override
    public Language language() {
        return Language.r;
    }

    @Override
    public GeneratedCode generate(AdapterQuery query, AdapterSetup setup) {
        VariableNames names = new VariableNames(RESERVED, "clause");
        List<String> lines = new ArrayList<>();
        lines.add("library(picsure)");
        lines.add("");
        lines.add(connect(setup));
        lines.add("");
        String root = query.phenotypicClause() == null ? null : clause(query.phenotypicClause(), names, lines);
        List<String> genomic = new ArrayList<>();
        for (AdapterQuery.Genomic filter : query.genomicFilters()) {
            String name = names.next(filter.key());
            lines.add(name + " <- picsure::buildGenomicFilter(" + genomicKey(filter.key()) + ", values = " + vector(filter.values()) + ")");
            genomic.add(name);
        }
        if (root != null || !genomic.isEmpty()) {
            lines.add("");
        }
        lines.addAll(buildQuery(root, query.includeConcepts(), genomic));
        lines.add("");
        lines.addAll(run(query.resultKind()));
        String code = String.join("\n", lines) + "\n";
        String install = "Rscript -e 'remotes::install_github(\"" + REPOSITORY + "@" + setup.rTag() + "\")'";
        return new GeneratedCode(Language.r, PACKAGE, setup.rTag(), RUNTIME, CHECK, install, code);
    }

    private static String connect(AdapterSetup setup) {
        StringBuilder line = new StringBuilder("session <- picsure::connect(").append(literal(setup.baseUrl()))
            .append(", token = Sys.getenv(\"PICSURE_TOKEN\"), include_consents = ").append(setup.includeConsents() ? "TRUE" : "FALSE");
        if (setup.supportsGenomic()) {
            line.append(", supports_genomic = TRUE");
        }
        return line.append(")").toString();
    }

    private static String clause(AdapterQuery.Clause clause, VariableNames names, List<String> lines) {
        if (clause instanceof AdapterQuery.Filter filter) {
            String name = names.forConceptPath(filter.conceptPath());
            StringBuilder call = new StringBuilder(name).append(" <- picsure::buildClause(").append(literal(filter.conceptPath()))
                .append(", type = picsure::PhenotypicFilterType$").append(FILTER_TYPES.get(filter.type()));
            if (!filter.categories().isEmpty()) {
                call.append(", categories = ").append(vector(filter.categories()));
            }
            if (filter.min() != null) {
                call.append(", min = ").append(PythonGenerator.number(filter.min()));
            }
            if (filter.max() != null) {
                call.append(", max = ").append(PythonGenerator.number(filter.max()));
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
            name + " <- picsure::buildClauseGroup(list(" + String.join(", ", children) + "), operator = picsure::GroupOperator$"
                + group.operator().name() + ")"
        );
        return name;
    }

    private static List<String> buildQuery(String root, List<String> includeConcepts, List<String> genomic) {
        List<List<String>> arguments = new ArrayList<>();
        if (root != null) {
            arguments.add(List.of("phenotypicFilter = " + root));
        }
        if (!includeConcepts.isEmpty()) {
            List<String> block = new ArrayList<>();
            block.add("includeConcepts = c(");
            for (int i = 0; i < includeConcepts.size(); i++) {
                block.add(INDENT + literal(includeConcepts.get(i)) + (i < includeConcepts.size() - 1 ? "," : ""));
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

    private static List<String> run(ResultKind kind) {
        return switch (kind) {
            case count -> List.of("count <- picsure::runQuery(session, query, type = \"count\")", "cat(count$raw, \"\\n\", sep = \"\")");
            case cross_count -> List.of(
                "counts <- picsure::runQuery(session, query, type = \"cross_count\")", "for (concept_path in names(counts)) {",
                INDENT + "cat(concept_path, \" \", counts[[concept_path]]$raw, \"\\n\", sep = \"\")", "}"
            );
            case participant, timestamp -> List.of(
                "dir.create(" + literal(RESULTS_DIRECTORY) + ", showWarnings = FALSE, recursive = TRUE)",
                "output_path <- " + literal(RESULTS_DIRECTORY + "/" + kind.name() + ".csv"),
                "df <- picsure::runQuery(session, query, type = " + literal(kind.name()) + ")",
                "picsure::exportCSV(session, df, output_path)",
                "cat(sprintf(\"Saved %d rows and %d columns to %s\\n\", nrow(df), ncol(df), output_path))"
            );
        };
    }

    private static String genomicKey(String key) {
        String member = GENOMIC_KEYS.get(key);
        return member == null ? literal(key) : "picsure::GenomicFilterKey$" + member;
    }

    private static String vector(List<String> values) {
        List<String> literals = values.stream().map(RGenerator::literal).toList();
        return "c(" + String.join(", ", literals) + ")";
    }

    /**
     * Writes a string as a double-quoted, ASCII-only R literal. Backslashes and quotes are escaped, newline, carriage return, and tab use
     * their short escapes, and every other control character and everything outside printable ASCII becomes {@code \\uNNNN} or
     * {@code \\U{NNNNNNNN}}. R refuses a string that mixes {@code \\x} and {@code \\u} escapes, so {@code \\x} is never used. A NUL
     * character or an unpaired surrogate cannot appear in an R string; the walk refuses both before a generator runs.
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
            } else if (cp >= 0x20 && cp < 0x7f) {
                out.appendCodePoint(cp);
            } else if (cp <= 0xffff) {
                out.append(String.format("\\u%04x", cp));
            } else {
                out.append(String.format("\\U{%08x}", cp));
            }
        });
        return out.append('"').toString();
    }
}
