package edu.harvard.dbmi.avillach.domain;

import java.util.List;
import java.util.Objects;

public class PaginatedSearchResult<T> {
    private final List<T> results;
    private final int page;
    private final int total;

    public PaginatedSearchResult(List<T> results, int page, int total) {
        this.results = results;
        this.page = page;
        this.total = total;
    }

    public List<T> getResults() {
        return results;
    }

    public int getPage() {
        return page;
    }

    public int getTotal() {
        return total;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        PaginatedSearchResult<?> that = (PaginatedSearchResult<?>) o;
        return page == that.page && total == that.total && Objects.equals(results, that.results);
    }

    @Override
    public int hashCode() {
        return Objects.hash(results, page, total);
    }
}
