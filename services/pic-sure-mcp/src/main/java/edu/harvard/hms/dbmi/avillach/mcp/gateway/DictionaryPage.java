package edu.harvard.hms.dbmi.avillach.mcp.gateway;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * A page of concepts as the dictionary serializes a Spring Data {@code PageImpl}. The total is read from the top-level
 * {@code totalElements} member, or from {@code page.totalElements} when the dictionary is upgraded to the nested page form.
 *
 * @param content the concepts on this page
 * @param totalElements the total count in the flat page form, or null
 * @param page the nested page block, or null in the flat form
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DictionaryPage(List<DictionaryConcept> content, Long totalElements, PageInfo page) {

    /**
     * The nested page block of the newer page serialization.
     *
     * @param totalElements the total count across all pages
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PageInfo(Long totalElements) {
    }

    /**
     * The concepts on this page.
     *
     * @return the concepts, never null
     */
    public List<DictionaryConcept> concepts() {
        return content == null ? List.of() : content;
    }

    /**
     * The total number of concepts that match the search.
     *
     * @return the total from whichever page form arrived, or the size of this page when neither carried one
     */
    public long total() {
        if (totalElements != null) {
            return totalElements;
        }
        if (page != null && page.totalElements() != null) {
            return page.totalElements();
        }
        return concepts().size();
    }
}
