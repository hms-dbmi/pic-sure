package edu.harvard.hms.dbmi.avillach.hpds.service.filesharing;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Writes test data to the directory shared with the uploader to test
 * uploading features
 */
@Service
public class TestDataService {

    private static final Logger LOG = LoggerFactory.getLogger(TestDataService.class);

    private final FileSystemService fileSystemService;

    @Autowired
    public TestDataService(FileSystemService fileSystemService) {
        this.fileSystemService = fileSystemService;
    }

    public boolean uploadTestFile(String uuid) {
        LOG.info("Writing test file for uuid {}", uuid);
       return fileSystemService.writeResultToFile(
           "test_data.txt",
           "This is a disposable test file",
           uuid
       );
    }
}
