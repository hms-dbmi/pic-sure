package edu.harvard.hms.dbmi.avillach.hpds.service.util;

import edu.harvard.dbmi.avillach.domain.PaginatedSearchResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class PaginatorTest {

    private final Paginator paginator = new Paginator();

    @Test
    public void paginate_validParams() {
        List<String> values = IntStream.range(0, 50).boxed().map(String::valueOf).collect(Collectors.toList());
        PaginatedSearchResult<String> paginatedSearchResult = paginator.paginate(values, 1, 20);
        assertEquals(50, paginatedSearchResult.getTotal());
        assertEquals(1, paginatedSearchResult.getPage());
        assertEquals(IntStream.range(0, 20).boxed().map(String::valueOf).collect(Collectors.toList()), paginatedSearchResult.getResults());
    }

    @Test
    public void paginate_lastPage() {
        List<String> values = IntStream.range(0, 50).boxed().map(String::valueOf).collect(Collectors.toList());
        PaginatedSearchResult<String> paginatedSearchResult = paginator.paginate(values, 3, 20);
        assertEquals(50, paginatedSearchResult.getTotal());
        assertEquals(3, paginatedSearchResult.getPage());
        assertEquals(IntStream.range(40, 50).boxed().map(String::valueOf).collect(Collectors.toList()), paginatedSearchResult.getResults());
    }


    @Test
    public void paginate_middlePage() {
        List<String> values = IntStream.range(0, 50).boxed().map(String::valueOf).collect(Collectors.toList());
        PaginatedSearchResult<String> paginatedSearchResult = paginator.paginate(values, 2, 20);
        assertEquals(50, paginatedSearchResult.getTotal());
        assertEquals(2, paginatedSearchResult.getPage());
        assertEquals(IntStream.range(20, 40).boxed().map(String::valueOf).collect(Collectors.toList()), paginatedSearchResult.getResults());
    }

    @Test
    public void paginate_pageOutOfBounds() {
        List<String> values = IntStream.range(0, 50).boxed().map(String::valueOf).collect(Collectors.toList());
        PaginatedSearchResult<String> paginatedSearchResult = paginator.paginate(values, 5, 20);
        assertEquals(50, paginatedSearchResult.getTotal());
        assertEquals(5, paginatedSearchResult.getPage());
        assertEquals(List.of(), paginatedSearchResult.getResults());
    }

    @Test
    public void paginate_noResults() {
        List<String> values = List.of();
        PaginatedSearchResult<String> paginatedSearchResult = paginator.paginate(values, 1, 20);
        assertEquals(0, paginatedSearchResult.getTotal());
        assertEquals(1, paginatedSearchResult.getPage());
        assertEquals(List.of(), paginatedSearchResult.getResults());
    }

    @Test
    public void paginate_invalidPageZero() {
        assertThrows(IllegalArgumentException.class, () -> {
            List<String> values = List.of();
            paginator.paginate(values, 0, 20);
        });
    }
    @Test
    public void paginate_invalidPageNegative() {
        assertThrows(IllegalArgumentException.class, () -> {
            List<String> values = List.of();
            paginator.paginate(values, -2, 20);
        });

    }

    @Test
    public void paginate_invalidSizeZero() {
        assertThrows(IllegalArgumentException.class, () -> {
            List<String> values = List.of();
            paginator.paginate(values, 1, 0);
        });
    }

    @Test
    public void paginate_invalidSizeNegative() {
        assertThrows(IllegalArgumentException.class, () -> {
            List<String> values = List.of();
            paginator.paginate(values, 1, -5);
        });
    }
}