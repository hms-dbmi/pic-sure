package edu.harvard.dbmi.avillach.dataupload.status;

import edu.harvard.dbmi.avillach.dataupload.hpds.hpdsartifactsdonotchange.Query;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.LocalDate;
import java.util.Optional;

@SpringBootTest
class StatusServiceTest {

    @Mock
    StatusRepository repository;

    @InjectMocks
    StatusService subject;

    @Test
    void shouldGetAndSet() {
        String initial = subject.getClientStatus();
        Assertions.assertEquals("uninitialized", initial);

        subject.setClientStatus("spooky");
        String actual = subject.getClientStatus();
        Assertions.assertEquals("spooky", actual);
    }

    @Test
    void shouldSetGenomicStatus() {
        Query q = new Query();
        q.setPicSureId(":)");

        subject.setGenomicStatus(q, UploadStatus.Uploading);

        Mockito.verify(repository, Mockito.times(1)).setGenomicStatus(":)", UploadStatus.Uploading);
    }

    @Test
    void shouldSetPhenotypicStatus() {
        Query q = new Query();
        q.setPicSureId(":)");

        subject.setPhenotypicStatus(q, UploadStatus.Uploading);

        Mockito.verify(repository, Mockito.times(1)).setPhenotypicStatus(":)", UploadStatus.Uploading);
    }

    @Test
    void shouldSetSite() {
        Query q = new Query();
        q.setPicSureId(":)");

        subject.setSite(q, "narnia");

        Mockito.verify(repository, Mockito.times(1)).setSite(":)", "narnia");
    }

    @Test
    void shouldApprove() {
        Query q = new Query();
        q.setPicSureId(":)");
        LocalDate now = LocalDate.now();

        subject.approve(q.getPicSureId(), now);

        Mockito.verify(repository, Mockito.times(1)).setApproved(":)", now);
    }

    @Test
    void shouldGetQueryStatus() {
        Query q = new Query();
        q.setPicSureId(":)");
        DataUploadStatuses statuses = new DataUploadStatuses(
            UploadStatus.Error, UploadStatus.Error, UploadStatus.Unsent, UploadStatus.Unsent,
            ":)", LocalDate.now(), "bch"
        );
        Mockito.when(repository.getQueryStatus(":)"))
            .thenReturn(Optional.of(statuses));

        Optional<DataUploadStatuses> actual = subject.getStatus(q.getPicSureId());
        Optional<DataUploadStatuses> expected = Optional.of(statuses);

        Assertions.assertEquals(expected, actual);
    }

    @Test
    void shouldSetPatientStatus() {
        Query q = new Query();
        q.setPicSureId(":)");

        subject.setPatientStatus(q, UploadStatus.Uploading);

        Mockito.verify(repository, Mockito.times(1)).setPatientStatus(":)", UploadStatus.Uploading);
    }

    @Test
    void shouldSetQueryStatus() {
        Query q = new Query();
        q.setPicSureId(":)");

        subject.setQueryUploadStatus(q, UploadStatus.Uploading);

        Mockito.verify(repository, Mockito.times(1)).setQueryUploadStatus(":)", UploadStatus.Uploading);
    }
}