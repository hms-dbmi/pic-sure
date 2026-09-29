package edu.harvard.hms.dbmi.avillach.mcp.query;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.ResultType;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.GenomicFilter;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.Operator;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.PhenotypicClause;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.PhenotypicFilter;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.PhenotypicFilterType;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.PhenotypicSubquery;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.Query;
import edu.harvard.hms.dbmi.avillach.mcp.tool.ToolArguments;
import edu.harvard.hms.dbmi.avillach.mcp.tool.ToolFailure;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.Set;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;

/**
 * The query a query tool accepts: the v3 {@link Query} without the fields the model must not set. {@code expectedResultType} is chosen by
 * the tool, and {@code authorizationFilters}, {@code picsureId}, {@code id}, and {@code not} on either clause type do not exist here, so
 * the strict binder rejects them as unknown fields. {@link #toQuery} maps the input onto the real record.
 *
 * @param select concept paths to select; cross counts pass them on, COUNT ignores them
 * @param phenotypicClause the phenotypic filter tree, a single filter or a nested subquery
 * @param genomicFilters genomic filters
 */
public record QueryInput(
    @Schema(
        requiredMode = NOT_REQUIRED, description = "Concept paths to select. Passed on for cross counts, ignored for a plain count"
    ) List<String> select,
    @Schema(
        requiredMode = NOT_REQUIRED, description = "A single filter, or a subquery that combines nested clauses with AND or OR"
    ) Clause phenotypicClause,
    @Schema(
        requiredMode = NOT_REQUIRED, description = "Genomic filters, for example {\"key\":\"Gene_with_variant\",\"values\":[\"APOE\"]}"
    ) List<GenomicFilter> genomicFilters
) {

    /** Longest concept path accepted in a filter or in {@code select}, in characters. */
    public static final int MAX_CONCEPT_PATH_LENGTH = 2000;

    /** Longest genomic filter key accepted, in characters. */
    public static final int MAX_GENOMIC_KEY_LENGTH = 200;

    /**
     * Builds the v3 query this input describes, with the result type the tool chose. {@code select} is dropped for COUNT. Authorization
     * filters stay empty, {@code picsureId} and {@code id} stay null, and every {@code not} stays null.
     *
     * @param expectedResultType the result type the tool sends
     * @return the v3 query
     * @throws ToolFailure if a filter, subquery, genomic filter, or select entry is incomplete or malformed
     */
    public Query toQuery(ResultType expectedResultType) {
        List<String> selected = expectedResultType == ResultType.COUNT || select == null ? null
            : select.stream().map(path -> ToolArguments.requireText("select", path, MAX_CONCEPT_PATH_LENGTH)).toList();
        PhenotypicClause clause = phenotypicClause == null ? null : phenotypicClause.toClause();
        List<GenomicFilter> genomic = genomicFilters == null ? null : genomicFilters.stream().map(QueryInput::checkGenomic).toList();
        return new Query(selected, List.of(), clause, genomic, expectedResultType, null, null);
    }

    private static GenomicFilter checkGenomic(GenomicFilter filter) {
        if (filter == null) {
            throw new ToolFailure("A genomic filter must not be null.");
        }
        ToolArguments.requireText("key", filter.key(), MAX_GENOMIC_KEY_LENGTH);
        return filter;
    }

    /** A phenotypic clause without {@code not}: a single {@link Filter} or a nested {@link Subquery}, told apart by their fields. */
    @JsonTypeInfo(use = JsonTypeInfo.Id.DEDUCTION)
    @JsonSubTypes({@JsonSubTypes.Type(Subquery.class), @JsonSubTypes.Type(Filter.class)})
    public sealed interface Clause permits Filter, Subquery {

        /**
         * Maps this clause onto the v3 model.
         *
         * @return the equivalent v3 clause with {@code not} left null
         * @throws ToolFailure if the clause or a nested clause is incomplete
         */
        PhenotypicClause toClause();
    }

    /**
     * {@link PhenotypicFilter} without {@code not}.
     *
     * @param phenotypicFilterType how the filter matches
     * @param conceptPath the concept path to match, as search_concepts returns it
     * @param values categorical values, not combined with min or max
     * @param min numeric lower bound
     * @param max numeric upper bound
     */
    public record Filter(
        @Schema(
            description = "FILTER matches values or a min/max range, REQUIRED matches any value, ANY_RECORD_OF matches any value of the path or its children"
        ) PhenotypicFilterType phenotypicFilterType, @Schema(description = "A concept path from search_concepts") String conceptPath,
        @Schema(
            requiredMode = NOT_REQUIRED, description = "Categorical values to match, for FILTER. Not combined with min or max"
        ) Set<String> values, @Schema(requiredMode = NOT_REQUIRED, description = "Numeric lower bound, for FILTER") Double min,
        @Schema(requiredMode = NOT_REQUIRED, description = "Numeric upper bound, for FILTER") Double max
    ) implements Clause {

        @Override
        public PhenotypicClause toClause() {
            if (phenotypicFilterType == null) {
                throw new ToolFailure("Field 'phenotypicFilterType' is required on every filter.");
            }
            ToolArguments.requireText("conceptPath", conceptPath, MAX_CONCEPT_PATH_LENGTH);
            return new PhenotypicFilter(phenotypicFilterType, conceptPath, values, min, max, null);
        }
    }

    /**
     * {@link PhenotypicSubquery} without {@code not}.
     *
     * @param phenotypicClauses the nested clauses, at least one
     * @param operator how the nested clauses combine
     */
    public record Subquery(
        @Schema(description = "The nested clauses, each a filter or another subquery") List<Clause> phenotypicClauses,
        @Schema(description = "AND matches participants who match every clause, OR those who match any") Operator operator
    ) implements Clause {

        @Override
        public PhenotypicClause toClause() {
            if (operator == null) {
                throw new ToolFailure("Field 'operator' is required on every subquery.");
            }
            if (phenotypicClauses == null || phenotypicClauses.isEmpty()) {
                throw new ToolFailure("Field 'phenotypicClauses' must hold at least one clause.");
            }
            List<PhenotypicClause> clauses = phenotypicClauses.stream().map(clause -> {
                if (clause == null) {
                    throw new ToolFailure("A phenotypic clause must not be null.");
                }
                return clause.toClause();
            }).toList();
            return new PhenotypicSubquery(null, clauses, operator);
        }
    }
}
