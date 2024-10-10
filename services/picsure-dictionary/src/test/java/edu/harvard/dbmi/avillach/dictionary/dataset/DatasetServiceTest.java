package edu.harvard.dbmi.avillach.dictionary.dataset;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;

import java.util.Map;
import java.util.Optional;

@SpringBootTest
class DatasetServiceTest {

    @MockBean
    DatasetRepository repository;

    @Autowired
    DatasetService subject;

    @Test
    void shouldGetDataset() {
        Mockito.when(repository.getDataset("foo")).thenReturn(Optional.of(new Dataset("foo", "1", "asdf", "idk")));
        Mockito.when(repository.getDatasetMeta("foo")).thenReturn(Map.of("key1", "val1", "key2", "val2"));

        Optional<Dataset> actual = subject.getDataset("foo");
        Dataset expected = new Dataset("foo", "1", "asdf", "idk", Map.of("key1", "val1", "key2", "val2"));

        Assertions.assertTrue(actual.isPresent());
        Assertions.assertEquals(expected, actual.get());
    }

    @Test
    void shouldNotGetDatasetThatDNE() {
        Mockito.when(repository.getDataset("foo")).thenReturn(Optional.empty());
        Mockito.when(repository.getDatasetMeta("foo")).thenReturn(Map.of());

        Optional<Dataset> actual = subject.getDataset("foo");

        Assertions.assertFalse(actual.isPresent());
    }
}
