package edu.harvard.dbmi.avillach.dictionary.concept.model;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.data.domain.Sort;

/**
 * The sort block of a concept page, mirroring what Jackson writes for a Spring Data {@link Sort}. The concept endpoints never sort, so
 * every page reports an empty, unsorted sort; the block is kept because the wire has always carried it.
 *
 * @param unsorted whether no sort order was applied
 * @param sorted whether a sort order was applied
 * @param empty whether the sort holds no orders
 */
@Schema(description = "Sort state of a concept page. The concept endpoints take no sort parameter, so this is always the unsorted state.")
@JsonPropertyOrder({"unsorted", "sorted", "empty"})
public record ConceptSort(
    @Schema(
        description = "True when no sort order was applied. Always true.", requiredMode = Schema.RequiredMode.REQUIRED
    ) boolean unsorted,
    @Schema(description = "True when a sort order was applied. Always false.", requiredMode = Schema.RequiredMode.REQUIRED) boolean sorted,
    @Schema(description = "True when the sort holds no orders. Always true.", requiredMode = Schema.RequiredMode.REQUIRED) boolean empty
) {

    /**
     * Copies the three flags Jackson serializes from a Spring Data sort.
     *
     * @param sort the sort a page or page request reports
     * @return the same state as a documented record
     */
    public static ConceptSort from(Sort sort) {
        return new ConceptSort(sort.isUnsorted(), sort.isSorted(), sort.isEmpty());
    }
}
