package edu.harvard.hms.dbmi.avillach.hpds.processing;


import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;

import static org.hamcrest.CoreMatchers.*;

public class ColumnSorterTest {

    @Test
    public void shouldSortColumns() {
        ColumnSorter subject = new ColumnSorter("a,b,c");
        List<String> actual = subject.sortInfoColumns(Set.of("b", "c", "a"));
        List<String> expected = List.of("a", "b", "c");

        assertEquals(expected, actual);
    }

    @Test
    public void shouldExcludeMissingColumns() {
        ColumnSorter subject = new ColumnSorter("a,b,c");
        List<String> actual = subject.sortInfoColumns(Set.of("d", "b", "c", "a"));
        List<String> expected = List.of("a", "b", "c");

        assertEquals(expected, actual);
    }

    @Test
    public void shouldNotBreakForMissingColumns() {
        ColumnSorter subject = new ColumnSorter("a,b,c,d");
        List<String> actual = subject.sortInfoColumns(Set.of("d", "a"));
        List<String> expected = List.of("a", "d");

        assertEquals(expected, actual);
    }

    @Test
    public void shouldNoOpWithoutConfig() {
        ColumnSorter subject = new ColumnSorter("");
        List<String> actual = subject.sortInfoColumns(Set.of("b", "c", "a"));
        List<String> expected = List.of("b", "c", "a");

        assertThat(new HashSet<>(expected), is(equalTo(new HashSet<>(actual))));
    }
}