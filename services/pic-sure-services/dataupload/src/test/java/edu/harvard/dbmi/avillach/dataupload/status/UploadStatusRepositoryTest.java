package edu.harvard.dbmi.avillach.dataupload.status;

import edu.harvard.dbmi.avillach.dataupload.hpds.Query;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.jdbc.Sql;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Optional;
import java.util.UUID;

@Testcontainers
@SpringBootTest
@Sql(scripts = {"/seed.sql"})
class UploadStatusRepositoryTest {

    @Container
    static final MySQLContainer<?> databaseContainer =
        new MySQLContainer<>("mysql:8").withReuse(true);

    @DynamicPropertySource
    static void mySQLProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", databaseContainer::getJdbcUrl);
        registry.add("spring.datasource.username", databaseContainer::getUsername);
        registry.add("spring.datasource.password", databaseContainer::getPassword);
        registry.add("spring.datasource.db", databaseContainer::getDatabaseName);
    }

    @Autowired
    UploadStatusRepository subject;

    @Test
    void shouldGetGenomicStatus() {
        Query query = new Query();
        query.setId(UUID.fromString("33613336-3934-3761-2d38-3233312d3131").toString());

        Optional<UploadStatus> actual = subject.getGenomicStatus(query.getId());
        Optional<UploadStatus> expected = Optional.of(UploadStatus.Complete);

        Assertions.assertEquals(expected, actual);
    }

    @Test
    void shouldGetPhenotypicStatus() {
        Query query = new Query();
        query.setId(UUID.fromString("33613336-3934-3761-2d38-3233312d3131").toString());

        Optional<UploadStatus> actual = subject.getPhenotypicStatus(query.getId());
        Optional<UploadStatus> expected = Optional.of(UploadStatus.Error);

        Assertions.assertEquals(expected, actual);
    }

    @Test
    void shouldSetGenomicStatus() {
        Query query = new Query();
        query.setId(UUID.fromString("33613336-3934-3761-2d38-3233312d3131").toString());

        subject.setGenomicStatus(query.getId(), UploadStatus.InProgress);
        Optional<UploadStatus> actual = subject.getGenomicStatus(query.getId());
        Optional<UploadStatus> expected = Optional.of(UploadStatus.InProgress);

        Assertions.assertEquals(expected, actual);
    }

    @Test
    void shouldSetPhenotypicStatus() {
        Query query = new Query();
        query.setId(UUID.fromString("33613336-3934-3761-2d38-3233312d3131").toString());

        subject.setPhenotypicStatus(query.getId(), UploadStatus.NotStarted);
        Optional<UploadStatus> actual = subject.getPhenotypicStatus(query.getId());
        Optional<UploadStatus> expected = Optional.of(UploadStatus.NotStarted);

        Assertions.assertEquals(expected, actual);
    }

    @Test
    void shouldSetStatusesOfNewQuery() {
        Query query = new Query();
        query.setId(UUID.randomUUID().toString());

        subject.setGenomicStatus(query.getId(), UploadStatus.InProgress);

        Optional<UploadStatus> actual = subject.getPhenotypicStatus(query.getId());
        Optional<UploadStatus> expected = Optional.of(UploadStatus.NotStarted);
        Assertions.assertEquals(expected, actual);
    }
}