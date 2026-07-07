package edu.harvard.hms.dbmi.avillach.hpds.service.filesharing;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertTrue;


public class TestDataServiceTest {
    FileSystemService fileSystemService;

    TestDataService subject;
    
    @Test
    public void shouldCreateTestFileForUpload() {
        fileSystemService = Mockito.mock(FileSystemService.class);
        subject = new TestDataService(fileSystemService);

        String uuid = UUID.randomUUID().toString();
        Mockito.when(fileSystemService.writeResultToFile("test_data.txt", "This is a disposable test file", uuid.toString()))
            .thenReturn(true);

        boolean success = subject.uploadTestFile(uuid);
        assertTrue(success);
        Mockito.verify(fileSystemService, Mockito.times(1))
            .writeResultToFile("test_data.txt", "This is a disposable test file", uuid.toString());
    }
}