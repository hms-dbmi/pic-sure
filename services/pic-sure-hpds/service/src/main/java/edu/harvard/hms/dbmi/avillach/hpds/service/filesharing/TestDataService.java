package edu.harvard.hms.dbmi.avillach.hpds.service.filesharing;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Writes test data to the shared file-export directory to test file-sharing features.
 */
@Service
public class TestDataService {

    private static final Logger LOG = LoggerFactory.getLogger(TestDataService.class);

    private final FileSystemV3Service fileSystemService;

    @Autowired
    public TestDataService(FileSystemV3Service fileSystemService) {
        this.fileSystemService = fileSystemService;
    }

    public boolean uploadTestFile(String uuid) {
        LOG.info("Writing test file for uuid {}", uuid);
        return fileSystemService.writeResultToFile("test_data.txt", "This is a disposable test file", uuid);
    }
}
