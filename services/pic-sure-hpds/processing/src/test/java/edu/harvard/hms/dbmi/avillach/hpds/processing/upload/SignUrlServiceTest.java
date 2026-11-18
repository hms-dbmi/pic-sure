package edu.harvard.hms.dbmi.avillach.hpds.processing.upload;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.File;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import edu.harvard.dbmi.avillach.logging.LoggingClient;
import edu.harvard.dbmi.avillach.logging.LoggingEvent;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

class SignUrlServiceTest {

    private static final String ACCESS_KEY_PROPERTY = "aws.accessKeyId";
    private static final String SECRET_KEY_PROPERTY = "aws.secretAccessKey";

    @TempDir
    Path exportDir;

    private LoggingClient loggingClient;
    private SignUrlService subject;

    /**
     * Upload service that skips the call to S3, so the audit event can be read without a bucket.
     */
    private static final class OfflineSignUrlService extends SignUrlService {

        private OfflineSignUrlService(LoggingClient loggingClient) {
            super("export-bucket", "us-east-1", 60, loggingClient);
        }

        @Override
        public void putS3Object(S3Client s3, String bucketName, String objectKey, File file) {}
    }

    @BeforeEach
    void setUp() {
        loggingClient = mock(LoggingClient.class);
        when(loggingClient.isEnabled()).thenReturn(true);
        subject = new OfflineSignUrlService(loggingClient);
    }

    @Test
    void uploadEventCarriesTheSizeOfTheUploadedFile() throws Exception {
        File export = Files.write(exportDir.resolve("a1b2c3.csv"), new byte[70_001]).toFile();

        subject.uploadFile(export, export.getName());

        ArgumentCaptor<LoggingEvent> captor = ArgumentCaptor.forClass(LoggingEvent.class);
        verify(loggingClient).send(captor.capture());
        LoggingEvent event = captor.getValue();
        assertEquals("data.s3.uploaded", event.getAction());
        assertEquals("export-bucket", event.getMetadata().get("bucket"));
        assertEquals("a1b2c3.csv", event.getMetadata().get("object_key"));
        assertEquals(70_001L, event.getMetadata().get("file_size_bytes"));
    }

    @Test
    void exportIsPutUnderItsKeyInTheBucketWithTheFileAsItsBody() throws Exception {
        byte[] rows = "patient_id,age\n17,42\n".getBytes(StandardCharsets.UTF_8);
        File export = Files.write(exportDir.resolve("a1b2c3.csv"), rows).toFile();
        S3Client s3 = mock(S3Client.class);

        new SignUrlService("export-bucket", "us-west-2", 60, loggingClient).putS3Object(s3, "export-bucket", "a1b2c3.csv", export);

        ArgumentCaptor<PutObjectRequest> request = ArgumentCaptor.forClass(PutObjectRequest.class);
        ArgumentCaptor<RequestBody> body = ArgumentCaptor.forClass(RequestBody.class);
        verify(s3).putObject(request.capture(), body.capture());
        assertEquals("export-bucket", request.getValue().bucket());
        assertEquals("a1b2c3.csv", request.getValue().key());
        try (InputStream sent = body.getValue().contentStreamProvider().newStream()) {
            assertArrayEquals(rows, sent.readAllBytes());
        }
    }

    @Test
    void presignedUrlNamesTheBucketRegionAndKeyAndExpiresAfterTheConfiguredMinutes() {
        SignUrlService fifteenMinuteLinks = new SignUrlService("export-bucket", "us-west-2", 15, loggingClient);

        URI url = URI.create(presignWithLocalCredentials(fifteenMinuteLinks, "a1b2c3.csv"));

        assertEquals("https", url.getScheme());
        assertEquals("export-bucket.s3.us-west-2.amazonaws.com", url.getHost());
        assertEquals("/a1b2c3.csv", url.getPath());
        List<String> query = List.of(url.getRawQuery().split("&"));
        assertTrue(query.contains("X-Amz-Expires=900"), "expiry in " + query);
        assertTrue(query.stream().anyMatch(parameter -> parameter.startsWith("X-Amz-Signature=")), "signature in " + query);
    }

    /**
     * Presigns a download link with credentials taken from system properties, so that signing, which makes no call to S3, does not depend
     * on the credentials of the machine running the test. The properties are put back afterwards.
     *
     * @param service the service that signs the link
     * @param objectKey the key of the object the link downloads
     * @return the presigned link
     */
    private static String presignWithLocalCredentials(SignUrlService service, String objectKey) {
        String accessKeyBefore = System.getProperty(ACCESS_KEY_PROPERTY);
        String secretKeyBefore = System.getProperty(SECRET_KEY_PROPERTY);
        System.setProperty(ACCESS_KEY_PROPERTY, "local-test-access-key");
        System.setProperty(SECRET_KEY_PROPERTY, "local-test-secret-key");
        try {
            return service.createPresignedGetUrl(objectKey);
        } finally {
            putBack(ACCESS_KEY_PROPERTY, accessKeyBefore);
            putBack(SECRET_KEY_PROPERTY, secretKeyBefore);
        }
    }

    private static void putBack(String property, String valueBefore) {
        if (valueBefore == null) {
            System.clearProperty(property);
        } else {
            System.setProperty(property, valueBefore);
        }
    }
}
