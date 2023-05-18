package edu.harvard.hms.dbmi.avillach.hpds.service.util;

import edu.harvard.dbmi.avillach.domain.PaginatedSearchResult;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class Paginator {

    /**
     * Creates a paginated search result with the specified page from a list
     *
     * @param list the list from which to select a page
     * @param page the page to select, the first page is 1
     * @param size the size of a page to select, minimum 1
     * @return A paginated search result containing the specified page
     */
    public <T> PaginatedSearchResult<T> paginate(List<T> list, int page, int size) {
        if (page < 1) {
            throw new IllegalArgumentException("Page must be greater than 0");
        }
        if (size < 1) {
            throw new IllegalArgumentException("Size must be greater than 0");
        }
        int start = Math.min((page - 1) * size, list.size());
        int end = Math.min(page * size, list.size());
        List<T> results = list.subList(start, end);
        return new PaginatedSearchResult<>(results, page, list.size());
    }
}
