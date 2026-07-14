package edu.harvard.dbmi.avillach.dataupload.upload;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.concurrent.Semaphore;

@SpringBootTest
class UploadConfigTest {

    @Test
    void shouldGetLock() {
        Semaphore lock = new UploadConfig().getUploadLock(1);

        Assertions.assertEquals(1, lock.availablePermits());
    }
}