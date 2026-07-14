package edu.harvard.dbmi.avillach.dataupload.hpds;

import edu.harvard.dbmi.avillach.dataupload.hpds.hpdsartifactsdonotchange.Query;
import edu.harvard.dbmi.avillach.dataupload.hpds.hpdsartifactsdonotchange.ResultType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.File;
import java.nio.file.Path;

@Service
public class HPDSConnectionVerifier {

    private static final Logger log = LoggerFactory.getLogger(HPDSConnectionVerifier.class);

    private final HPDSClient client;
    private final Path sharingRoot;
    private final UUIDGenerator uuidGenerator;

    @Autowired
    public HPDSConnectionVerifier(HPDSClient client, Path sharingRoot, UUIDGenerator uuidGenerator) {
        this.client = client;
        this.sharingRoot = sharingRoot;
        this.uuidGenerator = uuidGenerator;
    }

    public boolean verifyConnection() {
        log.info("Verifying connection to hpds by asking it to create a file and then verifying that the file exists.");
        Query testQuery = new Query();
        testQuery.setPicSureId(uuidGenerator.generate().toString());
        testQuery.setId(testQuery.getPicSureId());
        testQuery.setExpectedResultType(ResultType.COUNT);
        log.info("Created test query with UUID {}", testQuery.getPicSureId());

        log.info("Sending test query to HPDS");
        boolean hpdsResponse = client.writeTestData(testQuery);
        if (!hpdsResponse) {
            log.info("HPDS returned non 200 exit code. Assuming failure.");
            return false;
        }
        log.info("HPDS reported successfully writing the data. Verifying that the file exists");
        File testData = Path.of(sharingRoot.toString(), testQuery.getPicSureId(), "test_data.txt").toFile();

        if (testData.exists() && testData.isFile()) {
            log.info("File found! Connection to HPDS verified!");
            return testData.delete();
        }
        log.info(
            "File {} not found. HPDS is running, but the shared directory is probably misconfigured",
            testData.getPath()
        );
        return false;
    }
}
