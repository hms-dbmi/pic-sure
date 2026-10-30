package edu.harvard.hms.dbmi.avillach.ai.chat;

import java.util.List;
import java.util.Set;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.GenomicFilter;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.Operator;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.PhenotypicFilterType;

/**
 * The shape of {@code propose_query}'s {@code query} argument: the real {@code hpds.data.query.v3.Query} fields the model is allowed to
 * set, mirroring {@code pic-sure-mcp}'s own {@code query.QueryInput} -- {@code select}, {@code phenotypicClause} (a filter or a nested
 * subquery), and {@code genomicFilters} -- with the same exclusions: no {@code authorizationFilters}, {@code picsureId}, {@code id}, or
 * {@code not} on either clause type, since those must never come from the model.
 *
 * <p>Unlike {@code QueryInput}, there is no {@code toQuery()}/{@code toClause()}: this input is validated for shape only (see
 * {@link ProposeQueryTool}) and never executed or sent anywhere -- the final {@code ChatResponse} deliberately never carries a proposed
 * query yet (see {@code ToolUseLoopService}'s class Javadoc), so there is nothing to map this onto.
 *
 * @param select concept paths to select
 * @param phenotypicClause a single filter, or a subquery that combines nested clauses with AND or OR
 * @param genomicFilters genomic filters
 */
public record ProposeQueryInput(List<String> select, Clause phenotypicClause, List<GenomicFilter> genomicFilters) {

    /**
     * Checks this input and every nested clause and genomic filter for completeness.
     *
     * @throws IllegalArgumentException naming the first incomplete field found
     */
    public void validate() {
        if (phenotypicClause != null) {
            phenotypicClause.validate();
        }
        if (genomicFilters != null) {
            for (GenomicFilter filter : genomicFilters) {
                if (filter == null || filter.key() == null || filter.key().isBlank()) {
                    throw new IllegalArgumentException("Field 'key' is required on every genomic filter.");
                }
            }
        }
    }

    /** A phenotypic clause without {@code not}: a single {@link Filter} or a nested {@link Subquery}, told apart by their fields. */
    @JsonTypeInfo(use = JsonTypeInfo.Id.DEDUCTION)
    @JsonSubTypes({@JsonSubTypes.Type(Subquery.class), @JsonSubTypes.Type(Filter.class)})
    public sealed interface Clause permits Filter, Subquery {

        /**
         * Checks this clause and any nested clauses for completeness.
         *
         * @throws IllegalArgumentException naming the first incomplete field found
         */
        void validate();
    }

    /**
     * {@code PhenotypicFilter} without {@code not}.
     *
     * @param phenotypicFilterType how the filter matches
     * @param conceptPath the concept path to match
     * @param values categorical values to match, not combined with {@code min}/{@code max}
     * @param min numeric lower bound
     * @param max numeric upper bound
     */
    public record Filter(PhenotypicFilterType phenotypicFilterType, String conceptPath, Set<String> values, Double min, Double max)
        implements Clause {

        @Override
        public void validate() {
            if (phenotypicFilterType == null) {
                throw new IllegalArgumentException("Field 'phenotypicFilterType' is required on every filter.");
            }
            if (conceptPath == null || conceptPath.isBlank()) {
                throw new IllegalArgumentException("Field 'conceptPath' is required on every filter.");
            }
        }
    }

    /**
     * {@code PhenotypicSubquery} without {@code not}.
     *
     * @param phenotypicClauses the nested clauses, at least one
     * @param operator how the nested clauses combine
     */
    public record Subquery(List<Clause> phenotypicClauses, Operator operator) implements Clause {

        @Override
        public void validate() {
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
                clause.validate();
            }
        }
    }
}
