package edu.harvard.dbmi.avillach.dataupload.upload;

import edu.harvard.dbmi.avillach.dataupload.hpds.hpdsartifactsdonotchange.Query;
import edu.harvard.dbmi.avillach.dataupload.status.DataUploadStatuses;
import edu.harvard.dbmi.avillach.dataupload.status.UploadStatus;
import edu.harvard.dbmi.avillach.dataupload.status.StatusService;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@SpringBootTest
class DataUploadControllerTest {

    @Mock
    DataUploadService uploadService;

    @Mock
    StatusService statusService;

    @InjectMocks
    DataUploadController subject;

    @BeforeEach
    public void init() {
        ReflectionTestUtils.setField(subject, "institutions", List.of("bch", DataType.Genomic));
    }

    @Test
    void shouldUpload() {
        Query query = new Query();
        query.setPicSureId("my id");
        DataUploadStatuses before =
            new DataUploadStatuses(UploadStatus.Unsent, UploadStatus.Unsent, query.getPicSureId(), LocalDate.EPOCH, "bch");
        DataUploadStatuses after =
            new DataUploadStatuses(UploadStatus.Uploading, UploadStatus.Uploading, query.getPicSureId(), LocalDate.EPOCH, "bch");
        Mockito.when(statusService.getStatus(query.getPicSureId()))
            .thenReturn(Optional.of(before));
        Mockito.when(uploadService.asyncUpload(query, "bch", DataType.Genomic))
            .thenReturn(after);

        ResponseEntity<DataUploadStatuses> actual = subject.startUpload(query, "bch", DataType.Genomic);
        ResponseEntity<DataUploadStatuses> expected = ResponseEntity.ok(after);

        Mockito.verify(uploadService, Mockito.times(1))
            .asyncUpload(query, "bch", DataType.Genomic);
        Assertions.assertEquals(expected, actual);
    }

    @Test
    void shouldSayNotFound() {
        Query query = new Query();
        query.setPicSureId("my id");

        Mockito.when(statusService.getStatus(query.getPicSureId()))
            .thenReturn(Optional.empty());

        ResponseEntity<DataUploadStatuses> actual = subject.startUpload(query, "bch", DataType.Genomic);

        Assertions.assertEquals(HttpStatus.NOT_FOUND, actual.getStatusCode());
    }

    @Test
    void shouldSayNotFoundForSite() {
        Query query = new Query();
        query.setPicSureId("my id");

        ResponseEntity<DataUploadStatuses> actual = subject.startUpload(query, "foo", DataType.Genomic);

        Assertions.assertEquals(HttpStatus.NOT_FOUND, actual.getStatusCode());
    }

    @Test
    void shouldBlockUnapproved() {
        Query query = new Query();
        query.setPicSureId("my id");
        DataUploadStatuses nullApprovalDate =
            new DataUploadStatuses(UploadStatus.Unsent, UploadStatus.Unsent, query.getPicSureId(), null, "bch");
        Mockito.when(statusService.getStatus(query.getPicSureId()))
            .thenReturn(Optional.of(nullApprovalDate));

        ResponseEntity<DataUploadStatuses> actual = subject.startUpload(query, "bch", DataType.Genomic);

        Assertions.assertEquals(HttpStatus.UNAUTHORIZED, actual.getStatusCode());
    }

    @Test
    void shouldBlockApprovedInFuture() {
        Query query = new Query();
        query.setPicSureId("my id");
        DataUploadStatuses nullApprovalDate =
            new DataUploadStatuses(UploadStatus.Unsent, UploadStatus.Unsent, query.getPicSureId(), LocalDate.MAX, "bch");
        Mockito.when(statusService.getStatus(query.getPicSureId()))
            .thenReturn(Optional.of(nullApprovalDate));

        ResponseEntity<DataUploadStatuses> actual = subject.startUpload(query, "bch", DataType.Genomic);

        Assertions.assertEquals(HttpStatus.UNAUTHORIZED, actual.getStatusCode());
    }

    @Test
    void shouldNoOpWhenAlreadyUploading() {
        Query query = new Query();
        query.setPicSureId("my id");
        DataUploadStatuses uploading =
            new DataUploadStatuses(UploadStatus.Uploading, UploadStatus.Uploading, query.getPicSureId(), LocalDate.EPOCH, "bch");
        Mockito.when(statusService.getStatus(query.getPicSureId()))
            .thenReturn(Optional.of(uploading));

        ResponseEntity<DataUploadStatuses> actual = subject.startUpload(query, "bch", DataType.Genomic);

        Assertions.assertEquals(HttpStatus.ACCEPTED, actual.getStatusCode());
    }
}