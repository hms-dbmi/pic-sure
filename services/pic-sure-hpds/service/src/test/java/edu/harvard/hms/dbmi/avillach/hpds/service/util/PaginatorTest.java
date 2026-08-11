package edu.harvard.hms.dbmi.avillach.hpds.service.util;

import edu.harvard.dbmi.avillach.contracts.query.v3.PaginatedResponse;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class PaginatorTest {

    private final Paginator paginator = new Paginator();

    @Test
    public void page_validParams() {
        List<String> values = IntStream.range(0, 50).boxed().map(String::valueOf).collect(Collectors.toList());
        PaginatedResponse<String> page = paginator.page(values, 1, 20);
        assertEquals(50, page.total());
        assertEquals(1, page.page());
        assertEquals(IntStream.range(0, 20).boxed().map(String::valueOf).collect(Collectors.toList()), page.results());
    }

    @Test
    public void page_lastPage() {
        List<String> values = IntStream.range(0, 50).boxed().map(String::valueOf).collect(Collectors.toList());
        PaginatedResponse<String> page = paginator.page(values, 3, 20);
        assertEquals(50, page.total());
        assertEquals(3, page.page());
        assertEquals(IntStream.range(40, 50).boxed().map(String::valueOf).collect(Collectors.toList()), page.results());
    }

    @Test
    public void page_middlePage() {
        List<String> values = IntStream.range(0, 50).boxed().map(String::valueOf).collect(Collectors.toList());
        PaginatedResponse<String> page = paginator.page(values, 2, 20);
        assertEquals(50, page.total());
        assertEquals(2, page.page());
        assertEquals(IntStream.range(20, 40).boxed().map(String::valueOf).collect(Collectors.toList()), page.results());
    }

    @Test
    public void page_pageOutOfBounds() {
        List<String> values = IntStream.range(0, 50).boxed().map(String::valueOf).collect(Collectors.toList());
        PaginatedResponse<String> page = paginator.page(values, 5, 20);
        assertEquals(50, page.total());
        assertEquals(5, page.page());
        assertEquals(List.of(), page.results());
    }

    @Test
    public void page_noResults() {
        List<String> values = List.of();
        PaginatedResponse<String> page = paginator.page(values, 1, 20);
        assertEquals(0, page.total());
        assertEquals(1, page.page());
        assertEquals(List.of(), page.results());
    }

    @Test
    public void page_invalidPageZero() {
        assertThrows(IllegalArgumentException.class, () -> {
            List<String> values = List.of();
            paginator.page(values, 0, 20);
        });
    }

    @Test
    public void page_invalidPageNegative() {
        assertThrows(IllegalArgumentException.class, () -> {
            List<String> values = List.of();
            paginator.page(values, -2, 20);
        });
    }

    @Test
    public void page_invalidSizeZero() {
        assertThrows(IllegalArgumentException.class, () -> {
            List<String> values = List.of();
            paginator.page(values, 1, 0);
        });
    }

    @Test
    public void page_invalidSizeNegative() {
        assertThrows(IllegalArgumentException.class, () -> {
            List<String> values = List.of();
            paginator.page(values, 1, -5);
        });
    }
}
