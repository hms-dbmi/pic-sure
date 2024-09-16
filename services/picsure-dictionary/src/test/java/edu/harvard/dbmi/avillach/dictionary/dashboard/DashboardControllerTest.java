package edu.harvard.dbmi.avillach.dictionary.dashboard;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;

@SpringBootTest
class DashboardControllerTest {
    @MockBean
    private DashboardService service;

    @Autowired
    private DashboardController subject;

    @Test
    void shouldGetDashboard() {
        Dashboard dashboard = new Dashboard(List.of(), List.of());
        Mockito.when(service.getDashboard())
            .thenReturn(dashboard);

        ResponseEntity<Dashboard> actual = subject.getDashboard();

        Assertions.assertEquals(dashboard, actual.getBody());
        Assertions.assertEquals(HttpStatus.OK, actual.getStatusCode());
    }
}