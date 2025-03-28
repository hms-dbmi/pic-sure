package edu.harvard.dbmi.avillach.dataupload.hpds;

import edu.harvard.dbmi.avillach.dataupload.hpds.hpdsartifactsdonotchange.Query;
import edu.harvard.dbmi.avillach.dataupload.hpds.hpdsartifactsdonotchange.ResultType;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

@SpringBootTest
class HPDSConnectionVerifierTest {

    @MockitoBean
    private HPDSClient client;

    @MockitoBean
    private UUIDGenerator generator;

    private final Query query = new Query();
    {
        query.setPicSureId("9f1fc383-611b-4c6a-af37-a33c07feea5e");
        query.setId("9f1fc383-611b-4c6a-af37-a33c07feea5e");
        query.setExpectedResultType(ResultType.COUNT);
    }

    @Test
    void shouldFailWhenHPDS400s(@TempDir Path sharingRoot) {
        Mockito.when(client.writeTestData(query))
            .thenReturn(false);
        Mockito.when(generator.generate())
            .thenReturn(UUID.fromString("9f1fc383-611b-4c6a-af37-a33c07feea5e"));
        HPDSConnectionVerifier subject = new HPDSConnectionVerifier(client, sharingRoot, generator);

        boolean result = subject.verifyConnection();

        Assertions.assertFalse(result);
        Mockito.verify(client, Mockito.times(1))
            .writeTestData(query);
    }

    @Test
    void shouldFailWhenHPDSDoesNotWrite(@TempDir Path sharingRoot) {
        Mockito.when(client.writeTestData(query))
            .thenReturn(true);
        Mockito.when(generator.generate())
            .thenReturn(UUID.fromString("9f1fc383-611b-4c6a-af37-a33c07feea5e"));
        HPDSConnectionVerifier subject = new HPDSConnectionVerifier(client, sharingRoot, generator);

        boolean result = subject.verifyConnection();

        Assertions.assertFalse(result);
        Mockito.verify(client, Mockito.times(1))
            .writeTestData(query);
    }

    @Test
    void shouldFailWhenHPDSMakesADirectory(@TempDir Path sharingRoot) throws IOException {
        Files.createDirectory(Path.of(sharingRoot.toString(), "9f1fc383-611b-4c6a-af37-a33c07feea5e"));
        Files.createDirectory(Path.of(sharingRoot.toString(), "9f1fc383-611b-4c6a-af37-a33c07feea5e", "test_data.txt"));

        Mockito.when(client.writeTestData(query))
            .thenReturn(true);
        Mockito.when(generator.generate())
            .thenReturn(UUID.fromString("9f1fc383-611b-4c6a-af37-a33c07feea5e"));
        HPDSConnectionVerifier subject = new HPDSConnectionVerifier(client, sharingRoot, generator);

        Assertions.assertFalse(subject.verifyConnection());
        Mockito.verify(client, Mockito.times(1))
            .writeTestData(query);
    }

    @Test
    void shouldPass(@TempDir Path sharingRoot) throws IOException {
        Files.createDirectory(Path.of(sharingRoot.toString(), "9f1fc383-611b-4c6a-af37-a33c07feea5e"));
        Files.writeString(
            Path.of(sharingRoot.toString(), "9f1fc383-611b-4c6a-af37-a33c07feea5e", "test_data.txt"),
            "Howdy :)"
        );

        Mockito.when(client.writeTestData(query))
            .thenReturn(true);
        Mockito.when(generator.generate())
            .thenReturn(UUID.fromString("9f1fc383-611b-4c6a-af37-a33c07feea5e"));
        HPDSConnectionVerifier subject = new HPDSConnectionVerifier(client, sharingRoot, generator);

        Assertions.assertTrue(subject.verifyConnection());
        Mockito.verify(client, Mockito.times(1))
            .writeTestData(query);
    }
}