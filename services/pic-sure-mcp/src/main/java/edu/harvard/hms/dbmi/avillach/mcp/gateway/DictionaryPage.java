package edu.harvard.hms.dbmi.avillach.mcp.gateway;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A page of concepts as the dictionary serializes a Spring Data {@code PageImpl}. The total is read from the top-level
 * {@code totalElements} member, or from {@code page.totalElements} when the dictionary is upgraded to the nested page form.
 *
 * @param content the concepts on this page
 * @param totalElements the total count in the flat page form, or null
 * @param page the nested page block, or null in the flat form
 * @param termTotals for a term breakdown, how many concepts each term matches on its own, or null
 * @param matches for a term breakdown, the terms that match each concept of the page, or null
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DictionaryPage(
    List<DictionaryConcept> content, Long totalElements, PageInfo page, Map<String, Long> termTotals, List<TermMatch> matches
) {

    /**
     * A page without a term breakdown.
     *
     * @param content the concepts on this page
     * @param totalElements the total count in the flat page form, or null
     * @param page the nested page block, or null in the flat form
     */
    public DictionaryPage(List<DictionaryConcept> content, Long totalElements, PageInfo page) {
        this(content, totalElements, page, null, null);
    }

    /**
     * The terms that match one concept of a term-breakdown page.
     *
     * @param dataset the dataset of the concept
     * @param conceptPath the concept path
     * @param terms the matching terms, in the order the search gave them
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TermMatch(String dataset, String conceptPath, List<String> terms) {
    }

    /**
     * The nested page block of the newer page serialization.
     *
     * @param totalElements the total count across all pages
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PageInfo(Long totalElements) {
    }

    /**
     * The concepts on this page, without any null entry the response carried.
     *
     * @return the concepts, never null and holding no null
     */
    public List<DictionaryConcept> concepts() {
        return content == null ? List.of() : content.stream().filter(Objects::nonNull).toList();
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
