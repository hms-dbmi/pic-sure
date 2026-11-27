package edu.harvard.hms.dbmi.avillach.mcp.tool;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;

/**
 * The cells of an exact, consent-filtered cross count, capped at {@link CrossCountResult#MAX_CELLS}. Each cell holds an exact count, or is
 * marked {@code noData} when the query service has no values for that concept in the cohort, or {@code failed} when it could not count it.
 *
 * @param resultType the result type that ran
 * @param totalCells how many cells there are in all, before the cap
 * @param cells the cells kept, concept by concept in the order the query service returned them
 * @param cellsOmitted how many cells the cap dropped, when it dropped any
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AuthorizedCrossCountResult(
    @Schema(description = "The result type that ran") String resultType,
    @Schema(description = "How many cells there are in all, before the cap") int totalCells,
    @Schema(description = "The cells kept, concept by concept") List<Cell> cells,
    @Schema(requiredMode = NOT_REQUIRED, description = "How many cells the cap dropped from the end of the list") Integer cellsOmitted
) {

    /**
     * One exact cross-count cell. Exactly one of {@code count}, {@code noData}, and {@code failed} is set.
     *
     * @param conceptPath the concept path counted
     * @param category the category value, or the continuous value, for categorical and continuous cross counts
     * @param count the exact count
     * @param noData true when the query service has no values for this concept in the cohort
     * @param failed true when the query service could not count this cell
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Cell(
        @Schema(description = "The concept path counted") String conceptPath,
        @Schema(requiredMode = NOT_REQUIRED, description = "The category value, or the continuous value") String category,
        @Schema(requiredMode = NOT_REQUIRED, description = "The exact count") Integer count,
        @Schema(
            requiredMode = NOT_REQUIRED,
            description = "True when there are no values for this concept in the cohort, or the concept has no data here"
        ) Boolean noData,
        @Schema(
            requiredMode = NOT_REQUIRED, description = "True when the query service could not count this cell; it is not a zero"
        ) Boolean failed
    ) {
    }

    /**
     * Reads the authorized channel's cross-count body. A CROSS_COUNT or OBSERVATION_CROSS_COUNT body maps each concept path to a number. A
     * categorical or continuous body maps each concept path to an object of category or value to a number, and an empty object for a
     * concept with no values in the cohort. A number that is not a plain exact count, such as the {@code -1} HPDS writes for a concept it
     * failed to count, becomes a failed cell. Each expected path the body leaves out, which is how HPDS reports an observation count for a
     * concept with no data, becomes a {@code noData} cell after the returned ones. The cap applies last.
     *
     * @param resultType the result type that ran
     * @param body the parsed response body, a JSON object
     * @param expected the concept paths that should each have a cell, or an empty list when the body decides which concepts appear
     * @return the capped cells
     */
    public static AuthorizedCrossCountResult from(String resultType, JsonNode body, List<String> expected) {
        Capped cells = new Capped();
        for (Iterator<Map.Entry<String, JsonNode>> paths = body.fields(); paths.hasNext();) {
            Map.Entry<String, JsonNode> path = paths.next();
            JsonNode value = path.getValue();
            if (!value.isObject()) {
                cells.add(() -> counted(path.getKey(), null, value));
            } else if (value.isEmpty()) {
                cells.add(() -> new Cell(path.getKey(), null, null, true, null));
            } else {
                for (Iterator<Map.Entry<String, JsonNode>> categories = value.fields(); categories.hasNext();) {
                    Map.Entry<String, JsonNode> category = categories.next();
                    cells.add(() -> counted(path.getKey(), category.getKey(), category.getValue()));
                }
            }
        }
        expected.stream().filter(path -> !body.has(path)).forEach(path -> cells.add(() -> new Cell(path, null, null, true, null)));
        int omitted = cells.total - cells.kept.size();
        return new AuthorizedCrossCountResult(resultType, cells.total, List.copyOf(cells.kept), omitted == 0 ? null : omitted);
    }

    /** Counts every cell but builds only the first {@link CrossCountResult#MAX_CELLS}; the body itself is already parsed in full. */
    private static final class Capped {

        private final List<Cell> kept = new ArrayList<>();
        private int total;

        void add(Supplier<Cell> cell) {
            total++;
            if (kept.size() < CrossCountResult.MAX_CELLS) {
                kept.add(cell.get());
            }
        }
    }

    private static Cell counted(String conceptPath, String category, JsonNode value) {
        CountResult count = CountResult.parse(value.isValueNode() ? value.asText() : "");
        return count.isExact() ? new Cell(conceptPath, category, count.count(), null, null)
            : new Cell(conceptPath, category, null, null, true);
    }
}
