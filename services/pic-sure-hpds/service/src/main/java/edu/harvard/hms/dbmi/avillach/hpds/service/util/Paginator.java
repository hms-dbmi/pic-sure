package edu.harvard.hms.dbmi.avillach.hpds.service.util;

import edu.harvard.dbmi.avillach.contracts.query.v3.PaginatedResponse;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class Paginator {

    /**
     * Creates a page of the given list as the shared {@link PaginatedResponse} contract record. The legacy {@code pic-sure-api-model}
     * {@code PaginatedSearchResult} variant died with the v1 surface it existed for.
     *
     * @param list the list from which to select a page
     * @param page the page to select, the first page is 1
     * @param size the size of a page to select, minimum 1
     * @return A page of results plus its paging metadata
     */
    public <T> PaginatedResponse<T> page(List<T> list, int page, int size) {
        if (page < 1) {
            throw new IllegalArgumentException("Page must be greater than 0");
        }
        if (size < 1) {
            throw new IllegalArgumentException("Size must be greater than 0");
        }
        int start = Math.min((page - 1) * size, list.size());
        int end = Math.min(page * size, list.size());
        return new PaginatedResponse<>(list.subList(start, end), page, list.size());
    }
}
