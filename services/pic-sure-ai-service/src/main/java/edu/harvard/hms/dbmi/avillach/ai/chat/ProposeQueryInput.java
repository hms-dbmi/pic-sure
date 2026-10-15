package edu.harvard.hms.dbmi.avillach.ai.chat;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.UnaryOperator;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.GenomicFilter;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.Operator;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.PhenotypicFilterType;

/**
 * The shape of a {@code query} object, on both sides of the chat: {@code propose_query}'s {@code query} argument (model output) and the
 * request's current {@code query} (client input), both validated by the same code ({@link ChatStateValidator}). It carries the real
 * {@code hpds.data.query.v3.Query} fields the model is allowed to set, mirroring {@code pic-sure-mcp}'s own {@code query.QueryInput} --
 * {@code select}, {@code phenotypicClause} (a filter or a nested subquery), and {@code genomicFilters} -- with the same exclusions: no
 * {@code authorizationFilters}, {@code picsureId}, or {@code id}, since those must never come from the model. Unlike {@code QueryInput}, both
 * clause types keep their {@code not}: dropping it from the researcher's query would turn an exclusion into an inclusion in every proposal
 * built on that query.
 *
 * <p>Unlike {@code QueryInput}, there is no {@code toQuery()}/{@code toClause()}: this input is never executed or sent anywhere. A
 * validated one is serialized back out as the response's {@code query}, so what the UI receives is built from these checked fields, not
 * from whatever JSON the model wrote.
 *
 * @param select concept paths to select
 * @param phenotypicClause a single filter, or a subquery that combines nested clauses with AND or OR
 * @param genomicFilters genomic filters
 */
public record ProposeQueryInput(List<String> select, Clause phenotypicClause, List<GenomicFilter> genomicFilters) {

    /** Longest concept path accepted in a filter or in {@code select}, in characters. Same bound as {@code pic-sure-mcp}. */
    public static final int MAX_CONCEPT_PATH_LENGTH = 2000;

    public static final List<String> FIELDS = List.of("select", "phenotypicClause", "genomicFilters");

    /** Longest genomic filter key accepted, in characters. Same bound as {@code pic-sure-mcp}. */
    public static final int MAX_GENOMIC_KEY_LENGTH = 200;

    /** Deepest subquery nesting accepted; a query nested further than any person would build is refused rather than recursed into. */
    public static final int MAX_CLAUSE_DEPTH = 20;

    /**
     * Checks this input and every nested clause and genomic filter for completeness.
     *
     * @throws IllegalArgumentException naming the first incomplete field found
     */
    public void validate() {
        if (select != null) {
            for (String path : select) {
                requirePath("select", path);
            }
        }
        if (phenotypicClause != null) {
            phenotypicClause.validate(1);
        }
        if (genomicFilters != null) {
            for (GenomicFilter filter : genomicFilters) {
                if (filter == null || filter.key() == null || filter.key().isBlank()) {
                    throw new IllegalArgumentException("Field 'key' is required on every genomic filter.");
                }
                if (filter.key().length() > MAX_GENOMIC_KEY_LENGTH) {
                    throw new IllegalArgumentException("Field 'key' is too long on a genomic filter.");
                }
            }
        }
    }

    @JsonIgnore // otherwise serialized as an "empty" query field
    public boolean isEmpty() {
        return (select == null || select.isEmpty()) && phenotypicClause == null && (genomicFilters == null || genomicFilters.isEmpty());
    }

    /**
     * Rewrites every concept path (in {@code select} and in every filter), leaving the rest untouched.
     *
     * @param rewrite maps a path to the form to emit
     * @return the rewritten input
     */
    public ProposeQueryInput mapPaths(UnaryOperator<String> rewrite) {
        return new ProposeQueryInput(
            select == null ? null : select.stream().map(rewrite).toList(),
            phenotypicClause == null ? null : phenotypicClause.mapFilters(filter -> filter.withConceptPath(rewrite.apply(filter.conceptPath()))),
            genomicFilters
        );
    }

    /** @return this input with {@code not: false} written as an absent {@code not}, which means the same, so the two compare equal */
    public ProposeQueryInput withoutFalseNots() {
        return phenotypicClause == null ? this
            : new ProposeQueryInput(select, phenotypicClause.mapFilters(filter -> filter.isNegated() ? filter : filter.withNot(null)), genomicFilters);
    }

    /** The UI exports every variable it filters on, so each filtered path is added to {@code select}. */
    public ProposeQueryInput withFilterPathsSelected() {
        if (phenotypicClause == null) {
            return this;
        }
        Set<String> paths = new LinkedHashSet<>(select == null ? List.of() : select);
        phenotypicClause.addPathsTo(paths);
        return new ProposeQueryInput(List.copyOf(paths), phenotypicClause, genomicFilters);
    }

    private static void requirePath(String field, String path) {
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("Field '" + field + "' must not hold a blank concept path.");
        }
        if (path.length() > MAX_CONCEPT_PATH_LENGTH) {
            throw new IllegalArgumentException("Field '" + field + "' holds a concept path that is too long.");
        }
    }

    /**
     * A phenotypic clause: a single {@link Filter} or a nested {@link Subquery}, told apart by their fields. Both carry {@code not}, so its
     * presence never decides which one a clause is.
     */
    @JsonTypeInfo(use = JsonTypeInfo.Id.DEDUCTION)
    @JsonSubTypes({@JsonSubTypes.Type(Subquery.class), @JsonSubTypes.Type(Filter.class)})
    public sealed interface Clause permits Filter, Subquery {

        /**
         * Checks this clause and any nested clauses for completeness.
         *
         * @param depth how deeply this clause is nested, the top-level clause being 1
         * @throws IllegalArgumentException naming the first incomplete field found
         */
        void validate(int depth);

        /**
         * @param rewrite maps a filter to the form to emit
         * @return this clause with every filter in it rewritten
         */
        Clause mapFilters(UnaryOperator<Filter> rewrite);

        void addPathsTo(Set<String> paths);
    }

    /**
     * {@code PhenotypicFilter}.
     *
     * @param phenotypicFilterType how the filter matches
     * @param conceptPath the concept path to match
     * @param values categorical values to match, not combined with {@code min}/{@code max}
     * @param min numeric lower bound
     * @param max numeric upper bound
     * @param not true to match the participants the filter would otherwise exclude
     */
    public record Filter(
        PhenotypicFilterType phenotypicFilterType, String conceptPath, Set<String> values, Double min, Double max, Boolean not
    ) implements Clause {

        @JsonIgnore // otherwise serialized as a "negated" query field
        public boolean isNegated() {
            return Boolean.TRUE.equals(not);
        }

        Filter withConceptPath(String replacement) {
            return new Filter(phenotypicFilterType, replacement, values, min, max, not);
        }

        Filter withNot(Boolean replacement) {
            return new Filter(phenotypicFilterType, conceptPath, values, min, max, replacement);
        }

        @Override
        public void validate(int depth) {
            if (phenotypicFilterType == null) {
                throw new IllegalArgumentException("Field 'phenotypicFilterType' is required on every filter.");
            }
            if (conceptPath == null || conceptPath.isBlank()) {
                throw new IllegalArgumentException("Field 'conceptPath' is required on every filter.");
            }
            requirePath("conceptPath", conceptPath);
            if (values != null && !values.isEmpty() && (min != null || max != null)) {
                throw new IllegalArgumentException("A filter takes either 'values' or 'min' and 'max', not both.");
            }
            if (min != null && max != null && min > max) {
                throw new IllegalArgumentException("Field 'min' must not be greater than 'max'.");
            }
        }

        @Override
        public Clause mapFilters(UnaryOperator<Filter> rewrite) {
            return rewrite.apply(this);
        }

        @Override
        public void addPathsTo(Set<String> paths) {
            paths.add(conceptPath);
        }
    }

    /**
     * {@code PhenotypicSubquery}.
     *
     * @param phenotypicClauses the nested clauses, at least one
     * @param operator how the nested clauses combine
     * @param not carried through as sent; HPDS does not implement it on a subquery yet
     */
    public record Subquery(List<Clause> phenotypicClauses, Operator operator, Boolean not) implements Clause {

        @Override
        public void validate(int depth) {
            if (depth > MAX_CLAUSE_DEPTH) {
                throw new IllegalArgumentException("Subqueries are nested too deeply.");
            }
            if (operator == null) {
                throw new IllegalArgumentException("Field 'operator' is required on every subquery.");
            }
            if (phenotypicClauses == null || phenotypicClauses.isEmpty()) {
                throw new IllegalArgumentException("Field 'phenotypicClauses' must hold at least one clause.");
            }
            for (Clause clause : phenotypicClauses) {
                if (clause == null) {
                    throw new IllegalArgumentException("A phenotypic clause must not be null.");
                }
                clause.validate(depth + 1);
            }
        }

        @Override
        public Clause mapFilters(UnaryOperator<Filter> rewrite) {
            return new Subquery(phenotypicClauses.stream().map(clause -> clause.mapFilters(rewrite)).toList(), operator, not);
        }

        @Override
        public void addPathsTo(Set<String> paths) {
            phenotypicClauses.forEach(clause -> clause.addPathsTo(paths));
        }
    }
}
