package edu.harvard.dbmi.avillach.dataupload.upload;

import edu.harvard.dbmi.avillach.dataupload.hpds.Query;
import edu.harvard.dbmi.avillach.dataupload.status.DataUploadStatuses;
import edu.harvard.dbmi.avillach.dataupload.status.UploadStatus;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.ResponseEntity;

@SpringBootTest
class DataUploadControllerTest {

    @Mock
    DataUploadService uploadService;

    @InjectMocks
    DataUploadController subject;

    @Test
    void shouldUpload() {
        Query query = new Query();
        query.setId("my id");

        ResponseEntity<DataUploadStatuses> actual = subject.startUpload(query, "BCH");
        ResponseEntity<DataUploadStatuses> expected = ResponseEntity.ok(
            new DataUploadStatuses(UploadStatus.InProgress, UploadStatus.InProgress, query.getId())
        );

        Mockito.verify(uploadService, Mockito.times(1))
            .upload(query, "BCH");
        Assertions.assertEquals(expected, actual);
    }
}