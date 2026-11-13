package edu.harvard.hms.dbmi.avillach.hpds.data.query.v3;

import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import edu.harvard.hms.dbmi.avillach.hpds.data.query.ResultType;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.AuthorizationFilter;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.GenomicFilter;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.Operator;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.PhenotypicClause;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.PhenotypicFilter;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.PhenotypicFilterType;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.PhenotypicSubquery;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.Query;

/**
 * Writes a v3 {@link Query} as one fixed string, so that two queries that ask for the same thing give the same string on every JVM, with
 * every collection implementation and with every JSON library version. {@link QueryIdentity} hashes the string into a permanent database
 * key, so neither form below may ever change for a query that is already stored.
 *
 * <p><b>Two forms.</b> <ul> <li>The row form, {@link #of(Query)}, writes every member listed under Query below. Its hash is the id of the
 * query row, which is also the key of the HPDS result.</li> <li>The general form, {@link #generalOf(Query)}, is the row form without its
 * last two members, {@code expectedResultType} and {@code sample}. Its hash is the id of the general query: what the caller asks for,
 * whatever the result type and whether or not it is a sample. A sample and a full export, or two export formats, of one cohort therefore
 * have different row forms and one general form.</li> </ul> One method writes the members the two forms share, so they cannot drift apart.
 * The two forms of one query are never the same string, and no general form equals any row form: after {@code genomicFilters} a general
 * form ends with <code>}</code> where a row form continues with {@code ,"expectedResultType":}.
 *
 * <p>Each string is written by hand, member by member, in the order below. Neither uses {@code toString()}, a JSON mapper, or the iteration
 * order of any set or map.
 *
 * <p><b>Version.</b> The first member of both forms is {@code v}, the number in {@link #FORM_VERSION}. It names the set of rules the rest
 * of the string was written under. The general form has no version of its own.
 *
 * <p><b>Query.</b> <ul> <li>{@code select}: every path in the given order, because that order is the column order of the export. Null is
 * written as an empty list.</li> <li>{@code phenotypicClause}: the clause tree, or {@code null} when there is none. A missing clause and a
 * subquery with no clauses are different queries.</li> <li>{@code genomicFilters}: every filter in the given order. Null is written as an
 * empty list.</li> <li>{@code expectedResultType}, row form only: the enum constant name, or {@code null}.</li> <li>{@code sample}, row
 * form only: always written, as {@code true} or {@code false}.</li> <li>{@code id}, {@code picsureId} and {@code authorizationFilters} are
 * left out of both forms, so neither string depends on who is asking or on their consents.</li> </ul>
 *
 * <p><b>PhenotypicFilter.</b> {@code phenotypicFilterType} as the constant name or {@code null}, {@code conceptPath} as a string or
 * {@code null}, {@code values} sorted or {@code null}, {@code min}, {@code max}, {@code not}.
 *
 * <p><b>PhenotypicSubquery.</b> {@code not}, {@code phenotypicClauses} in the given order with null written as an empty list, and
 * {@code operator} as the constant name or {@code null}.
 *
 * <p><b>GenomicFilter.</b> {@code key} as a string or {@code null}, {@code values} in the given order or {@code null}, {@code min},
 * {@code max}.
 *
 * <p><b>Rules that apply everywhere.</b> <ul> <li>A set is sorted by {@link String#compareTo(String)}, which compares UTF-16 code units and
 * does not depend on the locale. A null element sorts first.</li> <li>A list keeps its order, duplicates included.</li> <li>For
 * {@code Query.select}, {@code Query.genomicFilters} and {@code PhenotypicSubquery.phenotypicClauses} a null collection and an empty one
 * are the same query and both are written as {@code []}, because the model accessors return an empty list for null.</li> <li>For
 * {@code PhenotypicFilter.values} and {@code GenomicFilter.values} null and empty are different queries: null is written as {@code null}
 * and empty as {@code []}. HPDS reads null values as "no value filter" and empty values as a filter that lists no value, so a filter with
 * empty values and a range matches nobody while the same filter with null values runs the range.</li> <li>A null {@code not} is written as
 * {@code false}, because an absent flag means "not negated".</li> <li>A number is written as {@code 0x} and the sixteen hex digits of its
 * IEEE 754 double bits. A {@code Float} is widened to a double first and every NaN has one bit pattern, so 1 and 1.0 are the same text.
 * Negative zero and zero keep their own bits and are different queries, because the HPDS range comparison tells them apart. A null number
 * is {@code null}.</li> <li>A string is written between double quotes. A double quote or a backslash is preceded by a backslash, and every
 * character outside printable ASCII is written as {@code \}{@code u} and four hex digits per UTF-16 code unit. The whole form is therefore
 * ASCII and two different strings never give the same text. Strings are not trimmed, case-folded or Unicode-normalized, because HPDS
 * matches concept paths and values exactly.</li> </ul>
 *
 * <p><b>New model members.</b> The class lists every record component it writes or leaves out. When the class is initialized, the list is
 * compared with the record components and the permitted clause subtypes of the model, and a difference fails with
 * {@link IllegalStateException}. The check runs on the first use of this class, which is the first query that needs an id, and not at
 * service startup. The tests in {@code QueryIdentityTest} run the same comparison, so a mismatch fails the build before it can reach a
 * running service. A member added to the model can therefore not be left out of the id unnoticed.
 */
public final class CanonicalQuery {

    /**
     * The version of the rules this class writes under, written as the first member of the row form and of the general form. Raise it on
     * every deliberate change of the rules. A string written under new rules then always differs from every string written under old rules,
     * so a query can never take the id of a different query that was stored before the change.
     *
     * <p>Both forms share this one number on purpose. A row is filed under its general query, and one general query holds at most one row
     * per result type and sample flag. With a version per form, raising only the row form's version would give a resubmitted query a new
     * row id under the general id it already has, and that row would collide with the one stored before the change. With one version the
     * two ids always change together.
     */
    static final int FORM_VERSION = 1;

    /** Records that are reachable from the query but never written, because the member that holds them is left out. */
    static final Set<Class<?>> IGNORED_RECORDS = Set.of(AuthorizationFilter.class);

    /** The clause subtypes {@link #writeClause} knows how to write. */
    static final Set<Class<?>> HANDLED_CLAUSE_TYPES = Set.of(PhenotypicFilter.class, PhenotypicSubquery.class);

    /**
     * Every record this class writes, with each of its components as {@code name type}, in declaration order. A component listed here is
     * either written by this class or left out on purpose.
     */
    static final Map<Class<? extends Record>, List<String>> HANDLED_COMPONENTS = Map.of(
        Query.class,
        List.of(
            component("select", listOf(String.class)), component("authorizationFilters", listOf(AuthorizationFilter.class)),
            component("phenotypicClause", PhenotypicClause.class), component("genomicFilters", listOf(GenomicFilter.class)),
            component("expectedResultType", ResultType.class), component("picsureId", UUID.class), component("id", UUID.class),
            component("sample", boolean.class)
        ), PhenotypicFilter.class,
        List.of(
            component("phenotypicFilterType", PhenotypicFilterType.class), component("conceptPath", String.class),
            component("values", "java.util.Set<java.lang.String>"), component("min", Double.class), component("max", Double.class),
            component("not", Boolean.class)
        ), PhenotypicSubquery.class,
        List.of(
            component("not", Boolean.class), component("phenotypicClauses", listOf(PhenotypicClause.class)),
            component("operator", Operator.class)
        ), GenomicFilter.class,
        List.of(
            component("key", String.class), component("values", listOf(String.class)), component("min", Float.class),
            component("max", Float.class)
        )
    );

    static {
        HANDLED_COMPONENTS.forEach(CanonicalQuery::requireHandled);
        requireHandledSubtypes(PhenotypicClause.class, HANDLED_CLAUSE_TYPES);
    }

    private CanonicalQuery() {}

    /**
     * Returns the row form of a query: every member of the general form, then {@code expectedResultType} and {@code sample}. See the class
     * description for the rules.
     *
     * @param query the query to write
     * @return the row form, in printable ASCII
     * @throws NullPointerException when the query is null
     */
    public static String of(Query query) {
        StringBuilder out = openWithSharedMembers(query);
        out.append(",\"expectedResultType\":");
        writeEnum(out, query.expectedResultType());
        out.append(",\"sample\":").append(query.sample() ? "true" : "false");
        return out.append('}').toString();
    }

    /**
     * Returns the general form of a query: the row form without {@code expectedResultType} and {@code sample}. Two queries that differ only
     * in those two members give the same string. See the class description for the rules.
     *
     * @param query the query to write
     * @return the general form, in printable ASCII
     * @throws NullPointerException when the query is null
     */
    public static String generalOf(Query query) {
        return openWithSharedMembers(query).append('}').toString();
    }

    /**
     * Starts a canonical form with the members the row form and the general form share: the opening brace, {@code v}, {@code select},
     * {@code phenotypicClause} and {@code genomicFilters}. The object is left open, so the caller appends the remaining members, if any,
     * and the closing brace.
     *
     * @param query the query to write
     * @return a builder that holds the shared members and no closing brace
     * @throws NullPointerException when the query is null
     */
    private static StringBuilder openWithSharedMembers(Query query) {
        Objects.requireNonNull(query, "query is null");
        StringBuilder out = new StringBuilder(256);
        out.append("{\"v\":").append(FORM_VERSION).append(",\"select\":");
        writeStrings(out, query.select());
        out.append(",\"phenotypicClause\":");
        writeClause(out, query.phenotypicClause());
        out.append(",\"genomicFilters\":[");
        String separator = "";
        for (GenomicFilter filter : orEmpty(query.genomicFilters())) {
            out.append(separator);
            writeGenomicFilter(out, filter);
            separator = ",";
        }
        return out.append(']');
    }

    /**
     * Returns the components of a record as {@code name type}, in declaration order, with the full generic type name.
     *
     * @param type the record class to describe
     * @return one entry per record component
     */
    static List<String> declaredComponents(Class<? extends Record> type) {
        return Arrays.stream(type.getRecordComponents())
            .map(recordComponent -> component(recordComponent.getName(), recordComponent.getGenericType().getTypeName())).toList();
    }

    /**
     * Checks that a record still has exactly the components this class was written for.
     *
     * @param type the record class to check
     * @param handled the components this class handles, as {@link #declaredComponents} writes them
     * @throws IllegalStateException when the record has gained, lost, renamed, reordered or retyped a component
     */
    static void requireHandled(Class<? extends Record> type, List<String> handled) {
        List<String> declared = declaredComponents(type);
        if (!declared.equals(handled)) {
            throw new IllegalStateException(
                "CanonicalQuery does not match the components of " + type.getName() + ". Handled: " + handled + ". Declared: " + declared
                    + ". Update CanonicalQuery before this type is used in a query id."
            );
        }
    }

    /**
     * Checks that a sealed type still permits exactly the subtypes this class was written for.
     *
     * @param sealedType the sealed type to check
     * @param handled the subtypes this class handles
     * @throws IllegalStateException when the sealed type permits a different set of subtypes
     */
    static void requireHandledSubtypes(Class<?> sealedType, Set<Class<?>> handled) {
        Set<Class<?>> permitted = Set.of(sealedType.getPermittedSubclasses());
        if (!permitted.equals(handled)) {
            List<String> permittedNames = permitted.stream().map(Class::getName).sorted().toList();
            List<String> handledNames = handled.stream().map(Class::getName).sorted().toList();
            throw new IllegalStateException(
                "CanonicalQuery does not match the subtypes of " + sealedType.getName() + ". Handled: " + handledNames + ". Permitted: "
                    + permittedNames + ". Update CanonicalQuery before this type is used in a query id."
            );
        }
    }

    private static String component(String name, Class<?> type) {
        return component(name, type.getTypeName());
    }

    private static String component(String name, String typeName) {
        return name + " " + typeName;
    }

    private static String listOf(Class<?> elementType) {
        return "java.util.List<" + elementType.getTypeName() + ">";
    }

    private static void writeClause(StringBuilder out, PhenotypicClause clause) {
        if (clause == null) {
            out.append("null");
            return;
        }
        switch (clause) {
            case PhenotypicFilter filter -> writeFilter(out, filter);
            case PhenotypicSubquery subquery -> writeSubquery(out, subquery);
        }
    }

    private static void writeFilter(StringBuilder out, PhenotypicFilter filter) {
        out.append("{\"clause\":\"filter\",\"phenotypicFilterType\":");
        writeEnum(out, filter.phenotypicFilterType());
        out.append(",\"conceptPath\":");
        writeString(out, filter.conceptPath());
        out.append(",\"values\":");
        Set<String> values = filter.values();
        writeNullableStrings(
            out, values == null ? null : values.stream().sorted(Comparator.nullsFirst(Comparator.naturalOrder())).toList()
        );
        out.append(",\"min\":");
        writeNumber(out, filter.min());
        out.append(",\"max\":");
        writeNumber(out, filter.max());
        out.append(",\"not\":").append(Boolean.TRUE.equals(filter.not()) ? "true" : "false");
        out.append('}');
    }

    private static void writeSubquery(StringBuilder out, PhenotypicSubquery subquery) {
        out.append("{\"clause\":\"subquery\",\"not\":").append(Boolean.TRUE.equals(subquery.not()) ? "true" : "false");
        out.append(",\"phenotypicClauses\":[");
        String separator = "";
        for (PhenotypicClause child : orEmpty(subquery.phenotypicClauses())) {
            out.append(separator);
            writeClause(out, child);
            separator = ",";
        }
        out.append("],\"operator\":");
        writeEnum(out, subquery.operator());
        out.append('}');
    }

    private static void writeGenomicFilter(StringBuilder out, GenomicFilter filter) {
        if (filter == null) {
            out.append("null");
            return;
        }
        out.append("{\"key\":");
        writeString(out, filter.key());
        out.append(",\"values\":");
        writeNullableStrings(out, filter.values());
        out.append(",\"min\":");
        writeNumber(out, filter.min());
        out.append(",\"max\":");
        writeNumber(out, filter.max());
        out.append('}');
    }

    private static <T> Collection<T> orEmpty(Collection<T> collection) {
        return collection == null ? List.of() : collection;
    }

    private static void writeNullableStrings(StringBuilder out, Collection<String> strings) {
        if (strings == null) {
            out.append("null");
            return;
        }
        writeStrings(out, strings);
    }

    private static void writeStrings(StringBuilder out, Collection<String> strings) {
        out.append('[');
        String separator = "";
        for (String string : orEmpty(strings)) {
            out.append(separator);
            writeString(out, string);
            separator = ",";
        }
        out.append(']');
    }

    private static void writeEnum(StringBuilder out, Enum<?> constant) {
        writeString(out, constant == null ? null : constant.name());
    }

    private static void writeNumber(StringBuilder out, Number number) {
        if (number == null) {
            out.append("null");
            return;
        }
        out.append("0x").append(HexFormat.of().toHexDigits(Double.doubleToLongBits(number.doubleValue())));
    }

    private static void writeString(StringBuilder out, String string) {
        if (string == null) {
            out.append("null");
            return;
        }
        out.append('"');
        for (int index = 0; index < string.length(); index++) {
            char character = string.charAt(index);
            if (character == '"' || character == '\\') {
                out.append('\\').append(character);
            } else if (character < 0x20 || character > 0x7e) {
                out.append("\\u").append(HexFormat.of().toHexDigits(character));
            } else {
                out.append(character);
            }
        }
        out.append('"');
    }
}
