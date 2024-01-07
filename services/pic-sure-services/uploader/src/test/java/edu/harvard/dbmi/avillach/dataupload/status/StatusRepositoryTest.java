package edu.harvard.dbmi.avillach.dataupload.status;

import edu.harvard.dbmi.avillach.dataupload.hpds.hpdsartifactsdonotchange.Query;
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

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

@Testcontainers
@SpringBootTest
@Sql(scripts = {"/seed.sql"})
class StatusRepositoryTest {

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
    StatusRepository subject;

    @Test
    void shouldGetGenomicStatus() {
        Query query = new Query();
        query.setPicSureId(UUID.fromString("33613336-3934-3761-2d38-3233312d3131").toString());

        Optional<UploadStatus> actual = subject.getQueryStatus(query.getPicSureId())
                .map(DataUploadStatuses::genomic);
        Optional<UploadStatus> expected = Optional.of(UploadStatus.Uploaded);

        Assertions.assertEquals(expected, actual);
    }

    @Test
    void shouldGetPhenotypicStatus() {
        Query query = new Query();
        query.setPicSureId(UUID.fromString("33613336-3934-3761-2d38-3233312d3131").toString());

        Optional<UploadStatus> actual = subject.getQueryStatus(query.getPicSureId())
                .map(DataUploadStatuses::phenotypic);
        Optional<UploadStatus> expected = Optional.of(UploadStatus.Error);

        Assertions.assertEquals(expected, actual);
    }

    @Test
    void shouldSetGenomicStatus() {
        Query query = new Query();
        query.setPicSureId(UUID.fromString("33613336-3934-3761-2d38-3233312d3131").toString());

        subject.setGenomicStatus(query.getPicSureId(), UploadStatus.Uploading);
        Optional<UploadStatus> actual = subject.getQueryStatus(query.getPicSureId())
                .map(DataUploadStatuses::genomic);
        Optional<UploadStatus> expected = Optional.of(UploadStatus.Uploading);

        Assertions.assertEquals(expected, actual);
    }

    @Test
    void shouldSetPhenotypicStatus() {
        Query query = new Query();
        query.setPicSureId(UUID.fromString("33613336-3934-3761-2d38-3233312d3131").toString());

        subject.setPhenotypicStatus(query.getPicSureId(), UploadStatus.Unsent);
        Optional<UploadStatus> actual = subject.getQueryStatus(query.getPicSureId())
                .map(DataUploadStatuses::phenotypic);
        Optional<UploadStatus> expected = Optional.of(UploadStatus.Unsent);

        Assertions.assertEquals(expected, actual);
    }

    @Test
    void shouldSetStatusesOfNewQuery() {
        Query query = new Query();
        query.setPicSureId(UUID.randomUUID().toString());

        subject.setGenomicStatus(query.getPicSureId(), UploadStatus.Uploading);

        Optional<UploadStatus> actual = subject.getQueryStatus(query.getPicSureId())
                .map(DataUploadStatuses::phenotypic);
        Optional<UploadStatus> expected = Optional.of(UploadStatus.Unsent);
        Assertions.assertEquals(expected, actual);
    }

    @Test
    void shouldGetApproved() {
        Query query = new Query();
        query.setPicSureId(UUID.fromString("33613336-3934-3761-2d38-3233312d3131").toString());

        Optional<LocalDate> actual = subject.getQueryStatus(query.getPicSureId()).map(DataUploadStatuses::approved);
        Optional<LocalDate> expected = Optional.of(LocalDate.of(2022, 2, 22));

        Assertions.assertEquals(expected, actual);
    }

    @Test
    void shouldSetApprovedOfNewQuery() {
        Query query = new Query();
        query.setPicSureId(UUID.randomUUID().toString());

        subject.setApproved(query.getPicSureId(), LocalDate.of(2023, 1, 2));
        Optional<LocalDate> actual = subject.getQueryStatus(query.getPicSureId()).map(DataUploadStatuses::approved);
        Optional<LocalDate> expected = Optional.of(LocalDate.of(2023, 1, 2));

        Assertions.assertEquals(expected, actual);
    }

    @Test
    void shouldGetQueryId() {
        Query query = new Query();
        query.setPicSureId(UUID.fromString("33613336-3934-3761-2d38-3233312d3131").toString());

        DataUploadStatuses actual = subject.getQueryStatus(query.getPicSureId()).orElseThrow();
        DataUploadStatuses expected = new DataUploadStatuses(
            UploadStatus.Uploaded, UploadStatus.Error, "33613336-3934-3761-2d38-3233312d3131",
            LocalDate.of(2022, 2, 22), "bch"
        );

        Assertions.assertEquals(expected, actual);
    }

    @Test
    void shouldSetSite() {
        Query query = new Query();
        query.setPicSureId(UUID.fromString("33613336-3934-3761-2d38-3233312d3131").toString());

        subject.setSite(query.getPicSureId(), "Narnia");
        Optional<String> actual = subject.getQueryStatus(query.getPicSureId())
            .map(DataUploadStatuses::site);
        Optional<String> expected = Optional.of("Narnia");

        Assertions.assertEquals(expected, actual);
    }
}