package edu.harvard.dbmi.avillach.dictionary.concept.model;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.data.domain.Pageable;

/**
 * The paging request echoed inside a concept page, mirroring what Jackson writes for a Spring Data {@code PageRequest}.
 *
 * @param pageNumber the zero-based page index that was requested
 * @param pageSize the page size that was requested
 * @param sort the sort that was requested, always unsorted
 * @param offset the number of concepts that precede this page
 * @param paged whether the request asked for a page, always true
 * @param unpaged whether the request asked for everything at once, always false
 */
@Schema(description = "The paging request a concept page answers, echoed back from the page_number and page_size query parameters.")
@JsonPropertyOrder({"pageNumber", "pageSize", "sort", "offset", "paged", "unpaged"})
public record ConceptPageable(
    @Schema(
        description = "Zero-based index of the requested page.", example = "0", requiredMode = Schema.RequiredMode.REQUIRED
    ) int pageNumber,
    @Schema(
        description = "Requested number of concepts per page.", example = "10", requiredMode = Schema.RequiredMode.REQUIRED
    ) int pageSize,
    @Schema(
        description = "Sort state of the request. Always the unsorted state.", requiredMode = Schema.RequiredMode.REQUIRED
    ) ConceptSort sort,
    @Schema(
        description = "Number of concepts that precede this page, pageNumber times pageSize.", example = "0",
        requiredMode = Schema.RequiredMode.REQUIRED
    ) long offset,
    @Schema(
        description = "True when the request asked for one page. Always true.", requiredMode = Schema.RequiredMode.REQUIRED
    ) boolean paged,
    @Schema(
        description = "True when the request asked for every concept at once. Always false.", requiredMode = Schema.RequiredMode.REQUIRED
    ) boolean unpaged
) {

    /**
     * Copies the fields Jackson serializes from a Spring Data paging request.
     *
     * @param pageable the paging request a page reports
     * @return the same state as a documented record
     */
    public static ConceptPageable from(Pageable pageable) {
        return new ConceptPageable(
            pageable.getPageNumber(), pageable.getPageSize(), ConceptSort.from(pageable.getSort()), pageable.getOffset(),
            pageable.isPaged(), pageable.isUnpaged()
        );
    }
}
