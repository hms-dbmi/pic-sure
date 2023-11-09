package edu.harvard.dbmi.avillach.dataupload.status;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class ClientStatusServiceTest {

    @Autowired
    ClientStatusService subject;

    @Test
    void shouldGetAndSet() {
        String initial = subject.getClientStatus();
        Assertions.assertEquals("uninitialized", initial);

        subject.setClientStatus("spooky");
        String actual = subject.getClientStatus();
        Assertions.assertEquals("spooky", actual);
    }
}