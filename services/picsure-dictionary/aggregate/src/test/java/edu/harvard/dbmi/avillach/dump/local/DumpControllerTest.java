package edu.harvard.dbmi.avillach.dump.local;

import edu.harvard.dbmi.avillach.dump.entities.DumpRow;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.http.ResponseEntity;

import java.time.LocalDateTime;
import java.util.List;

@SpringBootTest
class DumpControllerTest {

    @Autowired
    DumpController subject;

    @MockitoBean
    DumpService service;

    @Test
    void shouldGetDump() {
        Mockito.when(service.dumpTable(DumpTable.ConceptNode)).thenReturn(List.of());

        ResponseEntity<List<? extends DumpRow>> actual = subject.dumpTable(DumpTable.ConceptNode);

        Assertions.assertTrue(actual.getStatusCode().is2xxSuccessful());
        Assertions.assertEquals(List.of(), actual.getBody());
    }

    @Test
    void shouldGetLastUpdatedAsISOTimestamp() {
        LocalDateTime time = LocalDateTime.of(2020, 1, 1, 0, 0, 0, 0);
        Mockito.when(service.getLastUpdate()).thenReturn(time);

        ResponseEntity<String> actual = subject.getLastUpdated();

        Assertions.assertEquals(200, actual.getStatusCode().value());
        Assertions.assertEquals("2020-01-01T00:00:00.000", actual.getBody());
    }
}
