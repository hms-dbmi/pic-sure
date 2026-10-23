package edu.harvard.hms.dbmi.avillach.mcp.codegen;

import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.PhenotypicFilterType;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The part of a {@code picsure} adapter script that does not depend on the language: the order of its sections, the walk over the clause
 * tree, the adapter's names for filter types and genomic keys, and the escaping that makes a string an ASCII-only literal. A subclass
 * supplies the syntax: how a call, an assignment, a keyword argument, and an enum member are written, and the sections that differ in shape
 * ({@link #preamble}, {@link #connect}, {@link #buildQuery}, {@link #run}).
 */
abstract class ScriptGenerator implements LanguageGenerator {

    private static final Map<PhenotypicFilterType, String> FILTER_TYPES = Map.of(
        PhenotypicFilterType.FILTER, "FILTER", PhenotypicFilterType.REQUIRED, "REQUIRE", PhenotypicFilterType.ANY_RECORD_OF, "ANYRECORD"
    );

    private static final Map<String, String> GENOMIC_KEYS = Map.of(
        "Gene_with_variant", "GENE_WITH_VARIANT", "Variant_consequence_calculated", "VARIANT_CONSEQUENCE_CALCULATED",
        "Variant_frequency_as_text", "VARIANT_FREQUENCY_AS_TEXT", "Variant_class", "VARIANT_CLASS", "Variant_severity", "VARIANT_SEVERITY"
    );

    private final Set<String> reserved;

    /**
     * Creates the generator.
     *
     * @param reserved the lower-case names a variable must not take in the target language
     */
    ScriptGenerator(Set<String> reserved) {
        this.reserved = reserved;
    }

    @Override
    public final GeneratedCode generate(AdapterQuery query, AdapterSetup setup) {
        VariableNames names = new VariableNames(reserved, "clause");
        List<String> lines = new ArrayList<>(preamble());
        lines.add("");
        lines.add(connect(setup));
        lines.add("");
        String root = query.phenotypicClause() == null ? null : clause(query.phenotypicClause(), names, lines);
        List<String> genomic = new ArrayList<>();
        for (AdapterQuery.Genomic filter : query.genomicFilters()) {
            String name = names.next(filter.key());
            lines.add(assign(name, call("buildGenomicFilter", genomicKey(filter.key()), keyword("values", strings(filter.values())))));
            genomic.add(name);
        }
        if (root != null || !genomic.isEmpty()) {
            lines.add("");
        }
        lines.addAll(buildQuery(root, query.includeConcepts(), genomic));
        lines.add("");
        lines.addAll(run(query.resultKind()));
        return packaged(setup, String.join("\n", lines) + "\n");
    }

    /**
     * Writes the lines that load the adapter, ahead of the connection.
     *
     * @return the lines, without a trailing blank line
     */
    abstract List<String> preamble();

    /**
     * Writes the statement that opens the session.
     *
     * @param setup the deployment's connection details
     * @return the statement
     */
    abstract String connect(AdapterSetup setup);

    /**
     * Writes the statement that assembles the query from the variables built so far.
     *
     * @param root the variable holding the phenotypic clause, or null
     * @param includeConcepts the concept paths to include
     * @param genomic the variables holding the genomic filters
     * @return the lines
     */
    abstract List<String> buildQuery(String root, List<String> includeConcepts, List<String> genomic);

    /**
     * Writes the lines that run the query and report the result.
     *
     * @param kind the result the caller asked for
     * @return the lines
     */
    abstract List<String> run(ResultKind kind);

    /**
     * Wraps the finished code with the language's setup details.
     *
     * @param setup the deployment's adapter versions
     * @param code the finished script
     * @return the code and its setup
     */
    abstract GeneratedCode packaged(AdapterSetup setup, String code);

    /**
     * Writes an assignment.
     *
     * @param name the variable
     * @param expression the value
     * @return the statement
     */
    abstract String assign(String name, String expression);

    /**
     * Writes a call of an adapter function.
     *
     * @param function the function name without its package prefix
     * @param arguments the arguments, already written
     * @return the call
     */
    abstract String call(String function, String... arguments);

    /**
     * Writes a keyword argument.
     *
     * @param name the parameter name
     * @param value the value, already written
     * @return the argument
     */
    abstract String keyword(String name, String value);

    /**
     * Writes a reference to a member of one of the adapter's enums.
     *
     * @param type the enum name
     * @param member the member name
     * @return the reference
     */
    abstract String enumValue(String type, String member);

    /**
     * Writes one code point of a string literal as an escape sequence: {@code \\uNNNN} within the Basic Multilingual Plane, and the
     * language's own form beyond it. A subclass overrides this to give some code points, such as control characters, another form.
     *
     * @param codePoint a control character or a code point outside ASCII
     * @return the escape sequence
     */
    String escape(int codePoint) {
        return codePoint <= 0xffff ? String.format("\\u%04x", codePoint) : astral(codePoint);
    }

    /**
     * Writes a code point beyond U+FFFF as an escape sequence.
     *
     * @param codePoint a code point above U+FFFF
     * @return the escape sequence
     */
    abstract String astral(int codePoint);

    /**
     * Writes a list of strings, which R writes as a vector.
     *
     * @param values the strings, unquoted
     * @return the list
     */
    abstract String strings(List<String> values);

    /**
     * Writes a list of variables, which R writes as a list.
     *
     * @param names the variable names
     * @return the list
     */
    abstract String variables(List<String> names);

    private String genomicKey(String key) {
        String member = GENOMIC_KEYS.get(key);
        return member == null ? string(key) : enumValue("GenomicFilterKey", member);
    }

    private String clause(AdapterQuery.Clause clause, VariableNames names, List<String> lines) {
        if (clause instanceof AdapterQuery.Filter filter) {
            String name = names.forConceptPath(filter.conceptPath());
            List<String> arguments = new ArrayList<>();
            arguments.add(string(filter.conceptPath()));
            arguments.add(keyword("type", enumValue("PhenotypicFilterType", FILTER_TYPES.get(filter.type()))));
            if (!filter.categories().isEmpty()) {
                arguments.add(keyword("categories", strings(filter.categories())));
            }
            if (filter.min() != null) {
                arguments.add(keyword("min", number(filter.min())));
            }
            if (filter.max() != null) {
                arguments.add(keyword("max", number(filter.max())));
            }
            lines.add(assign(name, call("buildClause", arguments.toArray(String[]::new))));
            return name;
        }
        AdapterQuery.Group group = (AdapterQuery.Group) clause;
        List<String> children = new ArrayList<>();
        for (AdapterQuery.Clause child : group.clauses()) {
            children.add(clause(child, names, lines));
        }
        String name = names.next("group");
        lines.add(
            assign(
                name,
                call("buildClauseGroup", variables(children), keyword("operator", enumValue("GroupOperator", group.operator().name())))
            )
        );
        return name;
    }

    /**
     * Writes a number as a literal: a whole number without a fraction, anything else in Java's shortest round-trip form, which both Python
     * and R read back to the same value.
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
     * Writes a string as a double-quoted, ASCII-only literal. Backslashes and quotes are escaped, newline, carriage return, and tab use
     * their short escapes, printable ASCII is written as it is, and every other code point is left to {@link #escape}.
     *
     * @param value the string
     * @return the literal
     */
    final String string(String value) {
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
            } else {
                out.append(escape(cp));
            }
        });
        return out.append('"').toString();
    }
}
