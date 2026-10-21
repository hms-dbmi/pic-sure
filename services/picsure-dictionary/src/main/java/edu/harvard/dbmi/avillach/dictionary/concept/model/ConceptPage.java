package edu.harvard.dbmi.avillach.dictionary.concept.model;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.data.domain.Page;

import java.util.List;

/**
 * One page of concepts, carrying every key and value Jackson writes for a Spring Data {@code PageImpl}. {@link #from(Page)} copies each
 * value from a real page, so the record cannot drift from what the page computes.
 *
 * <p> {@code PageImpl} has no stable key order, because Jackson reads its getters in reflection order and that order changes between JVM
 * starts. The record fixes one of the orders it produces.
 *
 * <p> The concepts in {@code content} are written by {@link PageContentConceptSerializer}, without Jackson's leading type id.
 * {@code PageImpl} exposes its content as an untyped list, so each concept there carries {@code type} once, as its last property, while a
 * concept nested under {@code children} or {@code table} carries it twice.
 *
 * @param content the concepts on this page
 * @param pageable the paging request this page answers
 * @param totalElements the number of concepts that match across every page
 * @param totalPages the number of pages the matches fill
 * @param last whether this is the final page
 * @param numberOfElements the number of concepts on this page
 * @param sort the sort state, always unsorted
 * @param first whether this is the first page
 * @param size the requested page size
 * @param number the zero-based index of this page
 * @param empty whether this page holds no concepts
 */
@Schema(description = "One page of concepts, in the shape Spring Data's page has always had on this endpoint.")
@JsonPropertyOrder(
    {"content", "pageable", "totalElements", "totalPages", "last", "numberOfElements", "sort", "first", "size", "number", "empty"}
)
public record ConceptPage(
    @JsonSerialize(contentUsing = PageContentConceptSerializer.class) @Schema(
        description = "The concepts on this page. Each carries its type as the last property.", requiredMode = Schema.RequiredMode.REQUIRED
    ) List<Concept> content,
    @Schema(description = "The paging request this page answers.", requiredMode = Schema.RequiredMode.REQUIRED) ConceptPageable pageable,
    @Schema(
        description = "Number of concepts that match the filter across every page.", example = "1234",
        requiredMode = Schema.RequiredMode.REQUIRED
    ) long totalElements,
    @Schema(
        description = "Number of pages the matches fill at this page size.", example = "124", requiredMode = Schema.RequiredMode.REQUIRED
    ) int totalPages,
    @Schema(description = "True when no page follows this one.", requiredMode = Schema.RequiredMode.REQUIRED) boolean last,
    @Schema(
        description = "Number of concepts in content.", example = "10", requiredMode = Schema.RequiredMode.REQUIRED
    ) int numberOfElements,
    @Schema(
        description = "Sort state of the page. Always the unsorted state.", requiredMode = Schema.RequiredMode.REQUIRED
    ) ConceptSort sort, @Schema(description = "True when this is page 0.", requiredMode = Schema.RequiredMode.REQUIRED) boolean first,
    @Schema(description = "Requested number of concepts per page.", example = "10", requiredMode = Schema.RequiredMode.REQUIRED) int size,
    @Schema(description = "Zero-based index of this page.", example = "0", requiredMode = Schema.RequiredMode.REQUIRED) int number,
    @Schema(description = "True when content is empty.", requiredMode = Schema.RequiredMode.REQUIRED) boolean empty
) {

    /**
     * Copies every value Jackson serializes from a Spring Data page, so the record and the page it came from write the same JSON.
     *
     * @param page the page the controller built
     * @return the same page as a documented record
     */
    public static ConceptPage from(Page<Concept> page) {
        return new ConceptPage(
            page.getContent(), ConceptPageable.from(page.getPageable()), page.getTotalElements(), page.getTotalPages(), page.isLast(),
            page.getNumberOfElements(), ConceptSort.from(page.getSort()), page.isFirst(), page.getSize(), page.getNumber(), page.isEmpty()
        );
    }
}
