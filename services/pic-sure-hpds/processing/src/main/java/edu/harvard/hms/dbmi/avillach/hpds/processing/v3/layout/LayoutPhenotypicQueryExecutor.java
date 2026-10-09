package edu.harvard.hms.dbmi.avillach.hpds.processing.v3.layout;

import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.AuthorizationFilter;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.Operator;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.PhenotypicClause;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.PhenotypicFilter;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.PhenotypicFilterType;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.PhenotypicSubquery;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.Query;
import org.roaringbitmap.FastAggregation;
import org.roaringbitmap.RoaringBitmap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Evaluates the phenotypic part of a v3 query over the layout store with roaring bitmaps: the counterpart of
 * {@code PhenotypicQueryExecutor.getPatientSet}, with no boxed integer sets.
 *
 * <p>Semantics follow the Java executor. Each authorization filter becomes a FILTER on its concept and values, ANDed with the phenotypic
 * clause; with neither, the cohort is every patient. FILTER matches values exactly or a numeric range inclusively, REQUIRED is every
 * patient of the concept, ANY_RECORD_OF is every patient of every concept whose path starts with the filter's path, and a subquery combines
 * its children with AND or OR. An unknown concept matches no one. Requests the Java executor throws on (a subquery with no clauses or no
 * operator, a FILTER with neither values nor a range, values on a numeric concept, a range on a categorical one) throw
 * {@link IllegalArgumentException} here, checked before any block is loaded. A range whose minimum lies above every value, or above its
 * maximum, matches no one where Java throws.
 *
 * <p>Children of a subquery and the concepts of an ANY_RECORD_OF are evaluated in parallel, as the Rust engine does.
 */
@Component
@ConditionalOnProperty(name = StoreFormat.PROPERTY, havingValue = StoreFormat.LAYOUT)
public class LayoutPhenotypicQueryExecutor {

    private final LayoutPhenotypicObservationStore store;

    /**
     * @param store the layout store to evaluate against
     */
    @Autowired
    public LayoutPhenotypicQueryExecutor(LayoutPhenotypicObservationStore store) {
        this.store = store;
    }

    /**
     * The patients matching a query's authorization filters and phenotypic clause. Genomic filters are not considered.
     *
     * @param query the query
     * @return the cohort; possibly shared with the store, so callers must not modify it
     * @throws IllegalArgumentException where the Java executor throws on the request, see the class description
     * @throws LayoutFormatException when a block cannot be loaded
     */
    public RoaringBitmap getPatientSet(Query query) {
        List<PhenotypicClause> clauses = new ArrayList<>();
        for (AuthorizationFilter filter : query.authorizationFilters()) {
            clauses.add(new PhenotypicFilter(PhenotypicFilterType.FILTER, filter.conceptPath(), filter.values(), null, null, null));
        }
        if (query.phenotypicClause() != null) {
            clauses.add(query.phenotypicClause());
        }
        if (clauses.isEmpty()) {
            return store.getPatientIds();
        }
        clauses.forEach(this::checkStructure);
        return combine(clauses, Operator.AND);
    }

    /**
     * The patients matching one clause, after the same structural checks as {@link #getPatientSet}.
     *
     * @param clause a phenotypic clause
     * @return the matching patients; possibly shared with the store, so callers must not modify it
     */
    public RoaringBitmap evaluate(PhenotypicClause clause) {
        checkStructure(clause);
        return evaluateChecked(clause);
    }

    private void checkStructure(PhenotypicClause clause) {
        switch (clause) {
            case PhenotypicSubquery subquery -> {
                if (subquery.operator() == null) {
                    throw new IllegalArgumentException("A subquery needs an operator");
                }
                if (subquery.phenotypicClauses().isEmpty()) {
                    throw new IllegalArgumentException("A subquery needs at least one clause");
                }
                subquery.phenotypicClauses().forEach(this::checkStructure);
            }
            case PhenotypicFilter filter -> checkFilter(filter);
        }
    }

    private void checkFilter(PhenotypicFilter filter) {
        if (filter.phenotypicFilterType() != PhenotypicFilterType.FILTER) {
            return;
        }
        LayoutConcept concept = store.index().get(filter.conceptPath());
        if (filter.values() != null) {
            if (!filter.values().isEmpty() && concept != null && !concept.categorical()) {
                throw new IllegalArgumentException("Value filter on numeric concept " + filter.conceptPath());
            }
        } else if (filter.min() != null || filter.max() != null) {
            if (concept != null && concept.categorical()) {
                throw new IllegalArgumentException("Range filter on categorical concept " + filter.conceptPath());
            }
        } else {
            throw new IllegalArgumentException("Either values or one of min/max must be set for a filter");
        }
    }

    private RoaringBitmap evaluateChecked(PhenotypicClause clause) {
        return switch (clause) {
            case PhenotypicSubquery subquery -> combine(subquery.phenotypicClauses(), subquery.operator());
            case PhenotypicFilter filter -> evaluateFilter(filter);
        };
    }

    private RoaringBitmap combine(List<PhenotypicClause> clauses, Operator operator) {
        if (clauses.size() == 1) {
            return evaluateChecked(clauses.getFirst());
        }
        RoaringBitmap[] sets = clauses.parallelStream().map(this::evaluateChecked).toArray(RoaringBitmap[]::new);
        return switch (operator) {
            case AND -> FastAggregation.and(sets);
            case OR -> FastAggregation.or(sets);
        };
    }

    private RoaringBitmap evaluateFilter(PhenotypicFilter filter) {
        String path = filter.conceptPath();
        return switch (filter.phenotypicFilterType()) {
            case REQUIRED -> store.getAllKeys(path);
            case ANY_RECORD_OF -> anyRecordOf(path);
            case FILTER -> filter.values() != null ? store.getKeysForValues(path, filter.values())
                : store.getKeysForRange(path, filter.min(), filter.max());
        };
    }

    private RoaringBitmap anyRecordOf(String prefix) {
        RoaringBitmap[] sets = store.childConcepts(prefix).parallelStream().filter(concept -> concept.observationCount() > 0)
            .map(concept -> store.getAllKeys(concept.path())).toArray(RoaringBitmap[]::new);
        return sets.length == 0 ? new RoaringBitmap() : FastAggregation.or(sets);
    }
}
