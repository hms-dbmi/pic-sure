package edu.harvard.hms.dbmi.avillach.hpds.processing.upload;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import edu.harvard.dbmi.avillach.logging.LoggingClient;
import edu.harvard.dbmi.avillach.logging.LoggingEvent;
import software.amazon.awssdk.services.s3.S3Client;

class SignUrlServiceTest {

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
}
