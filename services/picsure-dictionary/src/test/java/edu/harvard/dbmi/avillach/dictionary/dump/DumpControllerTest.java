package edu.harvard.dbmi.avillach.dictionary.dump;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;

import java.util.List;

@SpringBootTest
class DumpControllerTest {

    @Autowired
    DumpController subject;

    @MockBean
    DumpService service;

    @Test
    void shouldGetDump() {
        int pageNumber = 10;
        int pageSize = 1000;
        Pageable page = Pageable.ofSize(pageSize).withPage(pageNumber);
        Mockito.when(service.getRowsForTable(page, DumpTable.ConceptNode)).thenReturn(List.of());

        ResponseEntity<List<List<String>>> actual = subject.dumpTable(DumpTable.ConceptNode, pageNumber, pageSize);

        Assertions.assertTrue(actual.getStatusCode().is2xxSuccessful());
        Assertions.assertEquals(List.of(), actual.getBody());
    }
}
