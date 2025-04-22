package edu.harvard.dbmi.avillach.dictionary.dump;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.domain.Pageable;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class DumpServiceTest {

    @Autowired
    DumpService subject;

    @MockBean
    DumpRepository repository;

    @Test
    void shouldGetRowsForTable() {
        Pageable page = Pageable.unpaged();
        Mockito.when(repository.getRowsForTable(page, DumpTable.FacetCategory)).thenReturn(List.of(List.of(":)")));

        List<List<String>> actual = subject.getRowsForTable(page, DumpTable.FacetCategory);
        List<List<String>> expected = List.of(List.of(":)"));

        Assertions.assertEquals(expected, actual);
    }
}
