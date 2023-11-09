package edu.harvard.dbmi.avillach.dataupload.aws;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.model.*;
import software.amazon.encryption.s3.S3EncryptionClient;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

@ConditionalOnProperty(name = "production", havingValue = "true")
@Component
public class S3StateVerifier {

    private static final String testTempPrefix = "S3_DAEMON_INIT_TEST";
    private static final Logger LOG = LoggerFactory.getLogger(S3StateVerifier.class);

    @Value("${aws.s3.home_institute_bucket}")
    private String bucketName;

    @Value("${aws.s3.access_key_id}")
    private String keyId;

    @Value("${aws.kms.key_id}")
    private String kmsKeyId;

    @Value("${aws.s3.institution:}")
    private List<String> institutions;

    @Autowired
    private SelfRefreshingS3Client client;

    @PostConstruct
    private void verifyS3Status() {
        institutions.forEach(inst -> Thread.ofVirtual().start(() -> asyncVerify(inst)));

    }

    private void asyncVerify(String institution) {
        LOG.info("Checking S3 connection...");
        createTempFileWithText()
            .map(p -> uploadFileFromPath(p, institution))
            .map(this::waitABit)
            .flatMap(s1 -> deleteFileFromBucket(s1, institution))
            .orElseThrow();
        LOG.info("S3 connection verified.");
    }

    private Optional<String> deleteFileFromBucket(String s, String institution) {
        LOG.info("Verifying delete capabilities");
        DeleteObjectRequest request = DeleteObjectRequest.builder().bucket(bucketName).key(s).build();
        DeleteObjectResponse deleteObjectResponse = client.getS3Client(institution).deleteObject(request);
        return deleteObjectResponse.deleteMarker() ? Optional.of(s) : Optional.empty();
    }

    private String waitABit(String s) {
        try {
            Thread.sleep(Duration.of(10, ChronoUnit.SECONDS));
        } catch (InterruptedException e) {
            LOG.warn("Error sleeping: ", e);
        }
        return s;
    }

    private String uploadFileFromPath(Path p, String institution) {
        LOG.info("Verifying upload capabilities");
        RequestBody body = RequestBody.fromFile(p.toFile());
        PutObjectRequest request = PutObjectRequest.builder()
            .bucket(bucketName)
            .serverSideEncryption(ServerSideEncryption.AWS_KMS)
            .ssekmsKeyId(kmsKeyId)
            .key(p.getFileName().toString())
            .build();
        client.getS3Client(institution).putObject(request, body);
        return p.getFileName().toString();
    }

    private Optional<Path> createTempFileWithText() {
        try {
            Path path = Files.createTempFile(testTempPrefix + "_" + keyId, null);
            Files.writeString(path, "Howdy!");
            return Optional.of(path);
        } catch (IOException e) {
            LOG.error("Failed to create temp file. Daemon will likely be unable to write to S3!");
            return Optional.empty();
        }
    }
}
