package edu.harvard.hms.dbmi.avillach.hpds.data.query.translation;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

import edu.harvard.hms.dbmi.avillach.hpds.data.query.Filter.DoubleFilter;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.Filter.FloatFilter;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.Query.VariantInfoFilter;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.GenomicFilter;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.Operator;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.PhenotypicClause;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.PhenotypicFilter;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.PhenotypicFilterType;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.PhenotypicSubquery;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.Query;

/**
 * Translates a legacy (v1) {@link edu.harvard.hms.dbmi.avillach.hpds.data.query.Query} into the v3 {@link Query} shape. Pure function, no
 * I/O. Faithful to v1 semantics: each any-record-of list is an OR of its paths, and the whole query is an AND of every filter family.
 * Multiple non-empty {@code variantInfoFilters} groups (an OR the flat v3 genomic list cannot express) raise
 * {@link UntranslatableQueryException} rather than being silently merged.
 */
public class QueryTranslator {

    private QueryTranslator() {}

    public static Query translate(edu.harvard.hms.dbmi.avillach.hpds.data.query.Query v1) throws UntranslatableQueryException {
        return new Query(
            buildSelect(v1), List.of(), buildPhenotypicClause(v1), buildGenomicFilters(v1), v1.getExpectedResultType(),
            parseUuid(v1.getPicSureId()), parseUuid(v1.getId())
        );
    }

    private static List<String> buildSelect(edu.harvard.hms.dbmi.avillach.hpds.data.query.Query v1) {
        LinkedHashSet<String> select = new LinkedHashSet<>();
        if (v1.getFields() != null) {
            select.addAll(v1.getFields());
        }
        if (v1.getCrossCountFields() != null) {
            select.addAll(v1.getCrossCountFields());
        }
        return new ArrayList<>(select);
    }

    private static PhenotypicClause buildPhenotypicClause(edu.harvard.hms.dbmi.avillach.hpds.data.query.Query v1) {
        List<PhenotypicClause> clauses = new ArrayList<>();

        // Category filters, sorted by concept path (v1 map is unordered).
        new TreeMap<>(nullSafe(v1.getCategoryFilters())).forEach(
            (path, values) -> clauses.add(new PhenotypicFilter(PhenotypicFilterType.FILTER, path, toSet(values), null, null, false))
        );

        // Numeric filters, sorted by concept path.
        new TreeMap<>(nullSafe(v1.getNumericFilters())).forEach(
            (path, f) -> clauses.add(new PhenotypicFilter(PhenotypicFilterType.FILTER, path, null, f.getMin(), f.getMax(), false))
        );

        // Required fields, in list order.
        for (String path : nullSafeList(v1.getRequiredFields())) {
            clauses.add(new PhenotypicFilter(PhenotypicFilterType.REQUIRED, path, null, null, null, false));
        }

        // Any-record-of groups: the anyRecordOf list first, then anyRecordOfMulti lists in order.
        for (List<String> group : anyRecordOfGroups(v1)) {
            clauses.add(anyRecordOfClause(group));
        }

        if (clauses.isEmpty()) {
            return null;
        }
        if (clauses.size() == 1) {
            return clauses.get(0);
        }
        return new PhenotypicSubquery(false, clauses, Operator.AND);
    }

    private static List<List<String>> anyRecordOfGroups(edu.harvard.hms.dbmi.avillach.hpds.data.query.Query v1) {
        List<List<String>> groups = new ArrayList<>();
        List<String> single = nullSafeList(v1.getAnyRecordOf());
        if (!single.isEmpty()) {
            groups.add(single);
        }
        for (List<String> group : nullSafeList(v1.getAnyRecordOfMulti())) {
            if (group != null && !group.isEmpty()) {
                groups.add(group);
            }
        }
        return groups;
    }

    /**
     * Expands a v1 any-record-of list into one {@code ANY_RECORD_OF} filter per path, OR'd together, rather than collapsing the list to a
     * single "highest level" concept path. Collapsing (suggested in review, since v3 HPDS matches all concepts below an ANY_RECORD_OF path)
     * is only equivalent when the list happens to be an ancestor plus its own descendants; v1 lists can contain unrelated branches, where a
     * single-path collapse would change results. The full expansion is faithful in both cases, at the cost of some redundancy in the
     * ancestor+descendants case.
     */
    private static PhenotypicClause anyRecordOfClause(List<String> paths) {
        List<PhenotypicClause> filters = new ArrayList<>();
        for (String path : paths) {
            filters.add(new PhenotypicFilter(PhenotypicFilterType.ANY_RECORD_OF, path, null, null, null, false));
        }
        if (filters.size() == 1) {
            return filters.get(0);
        }
        return new PhenotypicSubquery(false, filters, Operator.OR);
    }

    private static List<GenomicFilter> buildGenomicFilters(edu.harvard.hms.dbmi.avillach.hpds.data.query.Query v1)
        throws UntranslatableQueryException {
        List<VariantInfoFilter> nonEmpty = new ArrayList<>();
        for (VariantInfoFilter group : nullSafeList(v1.getVariantInfoFilters())) {
            if (isNonEmpty(group)) {
                nonEmpty.add(group);
            }
        }
        if (nonEmpty.isEmpty()) {
            return List.of();
        }
        if (nonEmpty.size() > 1) {
            throw new UntranslatableQueryException(
                "multiple variantInfoFilter groups (OR semantics) cannot be represented in a v3 flat genomic filter list"
            );
        }
        VariantInfoFilter group = nonEmpty.get(0);
        List<GenomicFilter> result = new ArrayList<>();
        if (group.categoryVariantInfoFilters != null) {
            new TreeMap<>(group.categoryVariantInfoFilters)
                .forEach((key, values) -> result.add(new GenomicFilter(key, values == null ? null : Arrays.asList(values), null, null)));
        }
        if (group.numericVariantInfoFilters != null) {
            new TreeMap<>(group.numericVariantInfoFilters)
                .forEach((key, f) -> result.add(new GenomicFilter(key, null, f.getMin(), f.getMax())));
        }
        return result;
    }

    private static boolean isNonEmpty(VariantInfoFilter group) {
        if (group == null) {
            return false;
        }
        boolean hasCategory = group.categoryVariantInfoFilters != null && !group.categoryVariantInfoFilters.isEmpty();
        boolean hasNumeric = group.numericVariantInfoFilters != null && !group.numericVariantInfoFilters.isEmpty();
        return hasCategory || hasNumeric;
    }

    private static Set<String> toSet(String[] values) {
        return values == null ? null : new LinkedHashSet<>(Arrays.asList(values));
    }

    private static UUID parseUuid(String value) {
        if (value == null) {
            return null;
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static <K, V> Map<K, V> nullSafe(Map<K, V> map) {
        return map == null ? Map.of() : map;
    }

    private static <T> List<T> nullSafeList(List<T> list) {
        return list == null ? List.of() : list;
    }
}
