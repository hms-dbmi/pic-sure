package edu.harvard.dbmi.avillach.dictionary.dashboard;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;

import java.util.List;
import java.util.Map;

@SpringBootTest
class DashboardServiceTest {
    @MockBean
    DashboardRepository repository;

    @MockBean
    List<DashboardColumn> columns;

    @Autowired
    DashboardService subject;

    @Test
    void shouldGetDashboard() {
        List<Map<String, String>> rows = List.of(Map.of("a", "1", "b", "2"));
        Mockito.when(repository.getRows())
            .thenReturn(rows);

        Dashboard actual = subject.getDashboard();

        Dashboard expected = new Dashboard(columns, rows);
        Assertions.assertEquals(expected, actual);
    }
}