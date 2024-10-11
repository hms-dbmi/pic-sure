package edu.harvard.dbmi.avillach.dictionary.dashboard;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

@SpringBootTest
class DashboardConfigTest {

    @Autowired
    DashboardConfig subject;

    @Test
    void shouldGetColumns() {
        List<DashboardColumn> actual = subject.getColumns();
        List<DashboardColumn> expected = List.of(
            new DashboardColumn("abbreviation", "Abbreviation"), new DashboardColumn("name", "Name"),
            new DashboardColumn("clinvars", "Clinical Variables"), new DashboardColumn("melast", "This one goes last"),
            new DashboardColumn("participants", "Participants")
        );

        Assertions.assertEquals(expected, actual);
    }
}
