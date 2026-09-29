package edu.harvard.hms.dbmi.avillach.mcp.tool;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;

/**
 * The cells of an obfuscated open-access cross count, capped at {@link #MAX_CELLS}. A CROSS_COUNT has one cell per concept path; a
 * CATEGORICAL_CROSS_COUNT has one per concept path and category value; a CONTINUOUS_CROSS_COUNT has one per concept path and bin. Fields
 * that do not apply are omitted from the JSON.
 *
 * @param resultType the result type that ran
 * @param totalCells how many cells the open channel returned
 * @param cells the cells kept, in the order the open channel returned them
 * @param cellsOmitted how many cells the cap dropped, when it dropped any
 * @param withheld true when no result was returned, which is how the open channel withholds small results
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CrossCountResult(
    @Schema(description = "The result type that ran") String resultType,
    @Schema(description = "How many cells were returned") int totalCells, @Schema(description = "The cells kept") List<Cell> cells,
    @Schema(requiredMode = NOT_REQUIRED, description = "How many cells the cap dropped") Integer cellsOmitted,
    @Schema(requiredMode = NOT_REQUIRED, description = WITHHELD_DESCRIPTION) Boolean withheld
) {

    /** The schema description of {@code withheld}. An empty body is not a threshold signal, so it names no participant count. */
    public static final String WITHHELD_DESCRIPTION =
        "True when no result was returned (the open channel withholds small results), so there are no cells";

    /** The most cells a result carries. */
    public static final int MAX_CELLS = 100;

    /**
     * One cross-count cell.
     *
     * @param conceptPath the concept path counted
     * @param category the category value or continuous bin, for categorical and continuous cross counts
     * @param count the obfuscated count
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Cell(
        @Schema(description = "The concept path counted") String conceptPath,
        @Schema(requiredMode = NOT_REQUIRED, description = "The category value or continuous bin") String category,
        @Schema(description = "The obfuscated count") CountResult count
    ) {
    }

    /**
     * The result for a response the open channel withheld.
     *
     * @param resultType the result type that ran
     * @return a result with no cells and {@code withheld} true
     */
    public static CrossCountResult withheld(String resultType) {
        return new CrossCountResult(resultType, 0, List.of(), null, true);
    }

    /**
     * Reads the open channel's cross-count body. A CROSS_COUNT body maps each concept path to a display string. A categorical or continuous
     * body maps each concept path to an object of category or bin to a count object, whose {@code display} is parsed; its {@code count} and
     * {@code variance} are not used, because a suppressed cell carries a count of 0 there.
     *
     * @param resultType the result type that ran
     * @param body the parsed response body, a JSON object
     * @return the capped cells
     */
    public static CrossCountResult from(String resultType, JsonNode body) {
        List<Cell> all = new ArrayList<>();
        for (Iterator<Map.Entry<String, JsonNode>> paths = body.fields(); paths.hasNext();) {
            Map.Entry<String, JsonNode> path = paths.next();
            JsonNode value = path.getValue();
            if (value.isObject() && !value.has("display")) {
                for (Iterator<Map.Entry<String, JsonNode>> categories = value.fields(); categories.hasNext();) {
                    Map.Entry<String, JsonNode> category = categories.next();
                    all.add(new Cell(path.getKey(), category.getKey(), CountResult.parse(display(category.getValue()))));
                }
            } else {
                all.add(new Cell(path.getKey(), null, CountResult.parse(display(value))));
            }
        }
        int omitted = Math.max(all.size() - MAX_CELLS, 0);
        List<Cell> kept = omitted == 0 ? List.copyOf(all) : List.copyOf(all.subList(0, MAX_CELLS));
        return new CrossCountResult(resultType, all.size(), kept, omitted == 0 ? null : omitted, null);
    }

    private static String display(JsonNode value) {
        if (value.isObject()) {
            return value.path("display").asText("");
        }
        return value.isValueNode() ? value.asText() : "";
    }
}
