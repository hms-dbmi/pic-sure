package edu.harvard.hms.dbmi.avillach.mcp.codegen;

import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.Operator;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.PhenotypicFilterType;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The language-neutral form every {@link LanguageGenerator} renders: the phenotypic clause tree, the genomic filters, the extra output
 * columns, and the result to ask for. Lists are in a fixed order, so rendering them in order is deterministic.
 *
 * @param resultKind the result the code asks for
 * @param phenotypicClause the root of the clause tree, or null when the query has none
 * @param genomicFilters the genomic filters, possibly empty
 * @param includeConcepts extra output columns, without duplicates, empty for a count
 */
public record AdapterQuery(ResultKind resultKind, Clause phenotypicClause, List<Genomic> genomicFilters, List<String> includeConcepts) {

    /**
     * Every concept path the query names: filter paths depth first, then the output columns, without duplicates.
     *
     * @return the concept paths in first-seen order
     */
    public List<String> conceptPaths() {
        Set<String> paths = new LinkedHashSet<>();
        collect(phenotypicClause, paths);
        paths.addAll(includeConcepts);
        return new ArrayList<>(paths);
    }

    private static void collect(Clause clause, Set<String> paths) {
        if (clause instanceof Filter filter) {
            paths.add(filter.conceptPath());
        } else if (clause instanceof Group group) {
            group.clauses().forEach(child -> collect(child, paths));
        }
    }

    /** A node of the clause tree: a {@link Filter} or a {@link Group}. */
    public sealed interface Clause permits Filter, Group {
    }

    /**
     * One filter on one concept path.
     *
     * @param conceptPath the concept path
     * @param type how the filter matches
     * @param categories categorical values to match, sorted, empty when there are none
     * @param min numeric lower bound, or null
     * @param max numeric upper bound, or null
     */
    public record Filter(String conceptPath, PhenotypicFilterType type, List<String> categories, Double min, Double max) implements Clause {
    }

    /**
     * Clauses combined under one operator.
     *
     * @param clauses the child clauses in input order, at least one
     * @param operator how the children combine
     */
    public record Group(List<Clause> clauses, Operator operator) implements Clause {
    }

    /**
     * One categorical genomic filter.
     *
     * @param key the annotation key
     * @param values the values that must match, in input order
     */
    public record Genomic(String key, List<String> values) {
    }
}
