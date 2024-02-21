package edu.harvard.dbmi.avillach.dataupload.upload;

import edu.harvard.dbmi.avillach.dataupload.aws.SelfRefreshingS3Client;
import edu.harvard.dbmi.avillach.dataupload.aws.SiteAWSInfo;
import edu.harvard.dbmi.avillach.dataupload.hpds.HPDSClient;
import edu.harvard.dbmi.avillach.dataupload.hpds.hpdsartifactsdonotchange.Query;
import edu.harvard.dbmi.avillach.dataupload.status.StatusService;
import edu.harvard.dbmi.avillach.dataupload.status.UploadStatus;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.Spy;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.util.ReflectionTestUtils;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.Semaphore;

@SpringBootTest
class DataUploadServiceTest {

    @Spy
    Semaphore uploadLock = new Semaphore(1);

    @Mock
    HPDSClient hpds;

    @Mock
    StatusService statusService;

    @Mock
    private Path sharingRoot;

    @Mock
    S3Client s3Client;

    @Mock
    SelfRefreshingS3Client s3;

    @InjectMocks
    DataUploadService subject;

    Map<String, SiteAWSInfo> roleARNs = Map.of("bch", new SiteAWSInfo("bch", "", "myid", "", ""));

    @Test
    void shouldNotUploadDataForHPDSError(@TempDir Path tempDir) throws InterruptedException {
        Query q = new Query();
        q.setPicSureId("my-id");
        q.setId("my-id");

        Mockito.when(sharingRoot.toString()).thenReturn(tempDir.toString());
        Mockito.when(hpds.writeGenomicData(q)).thenReturn(false);

        subject.uploadData(q, DataType.Genomic, "bch");

        Mockito.verify(statusService, Mockito.times(1)).setGenomicStatus(q, UploadStatus.Querying);
        Mockito.verify(statusService, Mockito.times(1)).setGenomicStatus(q, UploadStatus.Error);
        Mockito.verify(uploadLock, Mockito.times(1)).acquire();
        Mockito.verify(uploadLock, Mockito.times(1)).release();
    }

    @Test
    void shouldNotUploadDataIfFileDNE(@TempDir Path tempDir) throws InterruptedException {
        Query q = new Query();
        q.setPicSureId("my-id");
        q.setId("my-id");

        Mockito.when(sharingRoot.toString()).thenReturn(tempDir.toString());
        Mockito.when(hpds.writePhenotypicData(q)).thenReturn(true);

        subject.uploadData(q, DataType.Phenotypic, "bch");

        Mockito.verify(statusService, Mockito.times(1)).setPhenotypicStatus(q, UploadStatus.Querying);
        Mockito.verify(statusService, Mockito.times(1)).setPhenotypicStatus(q, UploadStatus.Uploading);
        Mockito.verify(statusService, Mockito.times(1)).setPhenotypicStatus(q, UploadStatus.Error);
        Mockito.verify(uploadLock, Mockito.times(1)).acquire();
        Mockito.verify(uploadLock, Mockito.times(1)).release();
    }

    @Test
    void shouldNotUploadDataIfAWSUpset(@TempDir Path tempDir) throws IOException, InterruptedException {
        Query q = new Query();
        q.setPicSureId("my-id");
        q.setId("my-id");

        Files.createDirectory(Path.of(tempDir.toString(), q.getPicSureId()));
        Files.writeString(Path.of(tempDir.toString(), q.getPicSureId(), DataType.Phenotypic.fileName), ":)");
        ReflectionTestUtils.setField(subject, "roleARNs", roleARNs);

        Mockito.when(sharingRoot.toString()).thenReturn(tempDir.toString());
        Mockito.when(hpds.writePhenotypicData(q)).thenReturn(true);
        Mockito.when(s3.getS3Client("bch")).thenReturn(s3Client);
        Mockito.when(s3Client.putObject(Mockito.any(PutObjectRequest.class), Mockito.any(RequestBody.class)))
                .thenThrow(AwsServiceException.builder().build());

        subject.uploadData(q, DataType.Phenotypic, "bch");

        Mockito.verify(statusService, Mockito.times(1)).setPhenotypicStatus(q, UploadStatus.Querying);
        Mockito.verify(statusService, Mockito.times(1)).setPhenotypicStatus(q, UploadStatus.Uploading);
        Mockito.verify(s3Client, Mockito.times(1)).putObject(Mockito.any(PutObjectRequest.class), Mockito.any(RequestBody.class));
        Mockito.verify(statusService, Mockito.times(1)).setPhenotypicStatus(q, UploadStatus.Error);
        Mockito.verify(uploadLock, Mockito.times(1)).acquire();
        Mockito.verify(uploadLock, Mockito.times(1)).release();
    }

    @Test
    void shouldUploadData(@TempDir Path tempDir) throws IOException, InterruptedException {
        Query q = new Query();
        q.setPicSureId("my-id");
        q.setId("my-id");

        Path fileToUpload = Path.of(tempDir.toString(), q.getPicSureId(), DataType.Phenotypic.fileName);
        Files.createDirectory(Path.of(tempDir.toString(), q.getPicSureId()));
        Files.writeString(fileToUpload, ":)");
        ReflectionTestUtils.setField(subject, "roleARNs", roleARNs);

        Mockito.when(sharingRoot.toString()).thenReturn(tempDir.toString());
        Mockito.when(hpds.writePhenotypicData(q)).thenReturn(true);
        Mockito.when(s3.getS3Client("bch")).thenReturn(s3Client);
        Mockito.when(s3Client.putObject(Mockito.any(PutObjectRequest.class), Mockito.any(RequestBody.class)))
            .thenReturn(Mockito.mock(PutObjectResponse.class));

        subject.uploadData(q, DataType.Phenotypic, "bch");

        Mockito.verify(statusService, Mockito.times(1)).setPhenotypicStatus(q, UploadStatus.Querying);
        Mockito.verify(statusService, Mockito.times(1)).setPhenotypicStatus(q, UploadStatus.Uploading);
        Mockito.verify(s3Client, Mockito.times(1)).putObject(Mockito.any(PutObjectRequest.class), Mockito.any(RequestBody.class));
        Mockito.verify(statusService, Mockito.times(1)).setPhenotypicStatus(q, UploadStatus.Uploaded);
        Assertions.assertFalse(Files.exists(fileToUpload));
        Mockito.verify(uploadLock, Mockito.times(1)).acquire();
        Mockito.verify(uploadLock, Mockito.times(1)).release();
    }
}