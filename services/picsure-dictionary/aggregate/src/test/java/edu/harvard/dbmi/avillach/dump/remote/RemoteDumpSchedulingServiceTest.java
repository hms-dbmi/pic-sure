package edu.harvard.dbmi.avillach.dump.remote;

import edu.harvard.dbmi.avillach.dump.remote.api.RemoteDictionaryAPI;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.Optional;

@SpringBootTest
@ActiveProfiles("aggregate")
class RemoteDumpSchedulingServiceTest {

    @MockitoBean
    RemoteDictionaryRepository repository;

    @MockitoBean
    RemoteDictionaryAPI api;

    @MockitoBean
    DataRefreshService dataRefreshService;

    @Autowired
    RemoteDumpSchedulingService subject;

    @Test
    void shouldUpdateNewDictionary() {
        Mockito.when(api.fetchUpdateTimestamp("bch")).thenReturn(Optional.of(LocalDateTime.now()));
        Mockito.when(api.fetchUpdateTimestamp("foo")).thenReturn(Optional.of(LocalDateTime.now()));
        Mockito.when(repository.getUpdateTimestamp("bch")).thenReturn(LocalDateTime.MIN);
        Mockito.when(repository.getUpdateTimestamp("foo")).thenReturn(LocalDateTime.MIN);

        subject.pollForUpdates();

        Mockito.verify(dataRefreshService, Mockito.times(1)).refreshDictionary(new RemoteDictionary("bch", "Boston Children's"));
        Mockito.verify(dataRefreshService, Mockito.times(1)).refreshDictionary(new RemoteDictionary("foo", "Foo Made Up Hospital"));
    }

    @Test
    void shouldNotUpdateCurrentDictionary() {
        Mockito.when(api.fetchUpdateTimestamp("bch")).thenReturn(Optional.of(LocalDateTime.MIN));
        Mockito.when(api.fetchUpdateTimestamp("foo")).thenReturn(Optional.of(LocalDateTime.MIN));
        Mockito.when(repository.getUpdateTimestamp("bch")).thenReturn(LocalDateTime.MIN);
        Mockito.when(repository.getUpdateTimestamp("foo")).thenReturn(LocalDateTime.MIN);

        subject.pollForUpdates();

        Mockito.verifyNoInteractions(dataRefreshService);
    }
}
