package edu.harvard.hms.dbmi.avillach.ai.chat;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.GenomicFilter;

/**
 * {@code propose_query}'s {@code query} argument: the filters as a whole, and the selected variables as a change to the researcher's own.
 *
 * <p>Asked for the whole {@code select}, gpt-oss sent only the variables it was adding about half the time, so the researcher's columns
 * were dropped; asked to remove one, it left it out correctly. Sending the change instead of the list makes both of those right: a variable
 * not named stays, and only one the model names in {@code removeSelect} goes. Filters stay whole, since they are an expression, not a list.
 *
 * @param addSelect concept paths to add to the selected variables
 * @param removeSelect concept paths to remove from the selected variables
 * @param select read as {@code addSelect}: a counted query, or a proposal written as reply text, carries the paths it shows here
 * @param phenotypicClause a single filter, or a subquery that combines nested clauses with AND or OR
 * @param genomicFilters genomic filters
 */
record QueryChange(
    List<String> addSelect, List<String> removeSelect, List<String> select, ProposeQueryInput.Clause phenotypicClause,
    List<GenomicFilter> genomicFilters
) {

    /** The fields of a {@code query} argument, for a model that sends them at the top level of the call. */
    static final List<String> FIELDS = List.of("addSelect", "removeSelect", "select", "phenotypicClause", "genomicFilters");

    /**
     * @return the filters, with every path to add (from {@code addSelect}, then {@code select}) as the selection
     * @throws IllegalArgumentException naming the first incomplete field found
     */
    ProposeQueryInput additions() {
        Map<String, String> adds = new LinkedHashMap<>();
        for (List<String> paths : Arrays.asList(addSelect, select)) {
            if (paths != null) {
                paths.forEach(path -> adds.putIfAbsent(path == null ? null : ConceptPaths.normalize(path), path));
            }
        }
        ProposeQueryInput input =
            new ProposeQueryInput(adds.isEmpty() ? null : new ArrayList<>(adds.values()), phenotypicClause, genomicFilters);
        input.validate();
        if (removeSelect != null) {
            removeSelect.forEach(path -> ProposeQueryInput.requirePath("removeSelect", path));
        }
        return input;
    }

    /** @return the paths to remove, never null */
    List<String> removals() {
        return removeSelect == null ? List.of() : removeSelect;
    }
}
