package edu.harvard.hms.dbmi.avillach.hpds.service.util;

import edu.harvard.dbmi.avillach.contracts.query.v3.PaginatedResponse;
import edu.harvard.dbmi.avillach.domain.PaginatedSearchResult;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class Paginator {

    /**
     * Creates a page of the given list as the shared {@link PaginatedResponse} contract record. This is the v3 form; {@link #paginate} is
     * the legacy {@code pic-sure-api-model} shape kept alive only for the v1 surface, and dies with it.
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

    /**
     * Creates a paginated search result with the specified page from a list
     *
     * @param list the list from which to select a page
     * @param page the page to select, the first page is 1
     * @param size the size of a page to select, minimum 1
     * @return A paginated search result containing the specified page
     */
    public <T> PaginatedSearchResult<T> paginate(List<T> list, int page, int size) {
        PaginatedResponse<T> paged = page(list, page, size);
        return new PaginatedSearchResult<>(paged.results(), paged.page(), paged.total());
    }
}
