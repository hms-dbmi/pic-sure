package edu.harvard.dbmi.avillach.dataupload.aws;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.model.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Optional;

@ConditionalOnProperty(name = "production", havingValue = "true")
@Component
public class S3StateVerifier {

    private static final String testTempPrefix = "S3_DAEMON_INIT_TEST";
    private static final Logger LOG = LoggerFactory.getLogger(S3StateVerifier.class);

    @Autowired
    private Map<String, SiteAWSInfo> sites;

    @Autowired
    private AWSClientBuilder clientBuilder;

    @PostConstruct
    private void verifyS3Status() {
        sites.values().forEach(inst -> Thread.ofVirtual().start(() -> asyncVerify(inst)));

    }

    private void asyncVerify(SiteAWSInfo institution) {
        LOG.info("Checking S3 connection to {} ...", institution.siteName());
        createTempFileWithText(institution)
            .flatMap(p -> uploadFileFromPath(p, institution))
            .map(this::waitABit)
            .flatMap(s1 -> deleteFileFromBucket(s1, institution))
            .orElseThrow();
        LOG.info("S3 connection to {} verified.", institution.siteName());
    }

    private Optional<String> deleteFileFromBucket(String s, SiteAWSInfo info) {
        LOG.info("Verifying delete capabilities");
        DeleteObjectRequest request = DeleteObjectRequest.builder().bucket(info.bucket()).key(s).build();
        return clientBuilder.buildClientForSite(info.siteName())
            .map(c -> c.deleteObject(request))
            .map(DeleteObjectResponse::deleteMarker)
            .map((ignored) -> s);
    }

    private String waitABit(String s) {
        try {
            Thread.sleep(Duration.of(10, ChronoUnit.SECONDS));
        } catch (InterruptedException e) {
            LOG.warn("Error sleeping: ", e);
        }
        return s;
    }

    private Optional<String> uploadFileFromPath(Path p, SiteAWSInfo info) {
        LOG.info("Verifying upload capabilities");
        RequestBody body = RequestBody.fromFile(p.toFile());
        PutObjectRequest request = PutObjectRequest.builder()
            .bucket(info.bucket())
            .serverSideEncryption(ServerSideEncryption.AWS_KMS)
            .ssekmsKeyId(info.kmsKeyID())
            .key(p.getFileName().toString())
            .build();
        return clientBuilder.buildClientForSite(info.siteName())
            .map(client -> client.putObject(request, body))
            .map(resp -> p.getFileName().toString());
    }

    private Optional<Path> createTempFileWithText(SiteAWSInfo info) {
        try {
            Path path = Files.createTempFile(testTempPrefix + "_" + info.siteName(), null);
            Files.writeString(path, "Howdy!");
            return Optional.of(path);
        } catch (IOException e) {
            LOG.error("Failed to create temp file. Daemon will likely be unable to write to S3!");
            return Optional.empty();
        }
    }
}
