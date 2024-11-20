package edu.harvard.hms.dbmi.avillach.hpds.processing.upload;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;

import java.io.File;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

@Component
public class SignUrlService {

    private final String bucketName;
    private final int signedUrlExpiryMinutes;
    private final Region region;

    private static Logger log = LoggerFactory.getLogger(SignUrlService.class);

    @Autowired
    public SignUrlService(
            @Value("${data-export.s3.bucket-name:}") String bucketName,
            @Value("${data-export.s3.region:us-east-1}") String region,
            @Value("${data-export.s3.signedUrl-expiry-minutes:60}") int signedUrlExpiryMinutes
    ) {
        this.bucketName = bucketName;
        this.signedUrlExpiryMinutes = signedUrlExpiryMinutes;
        this.region = Region.of(region);
    }

    public void uploadFile(File file, String objectKey) {
        S3Client s3 = S3Client.builder()
                .region(this.region)
                .build();
        putS3Object(s3, bucketName, objectKey, file);
        s3.close();
    }

    // This example uses RequestBody.fromFile to avoid loading the whole file into
    // memory.
    public void putS3Object(S3Client s3, String bucketName, String objectKey, File file) {
        Map<String, String> metadata = new HashMap<>();
        PutObjectRequest putOb = PutObjectRequest.builder()
                .bucket(bucketName)
                .key(objectKey)
                .metadata(metadata)
                .build();

        s3.putObject(putOb, RequestBody.fromFile(file));
        log.info("Successfully placed " + objectKey + " into bucket " + bucketName);
    }

    public String createPresignedGetUrl(String keyName) {
        PresignedGetObjectRequest presignedRequest;
        try (S3Presigner presigner = S3Presigner.builder().region(region).build()) {
            GetObjectRequest objectRequest = GetObjectRequest.builder()
                    .bucket(bucketName)
                    .key(keyName)
                    .build();

            GetObjectPresignRequest presignRequest = GetObjectPresignRequest.builder()
                    .signatureDuration(Duration.ofMinutes(signedUrlExpiryMinutes))  // The URL will expire in 10 minutes.
                    .getObjectRequest(objectRequest)
                    .build();

            presignedRequest = presigner.presignGetObject(presignRequest);
        }
        log.info("Presigned URL: [{}]", presignedRequest.url().toString());

        return presignedRequest.url().toExternalForm();
    }
}
