package edu.harvard.hms.dbmi.avillach.hpds.test;

import edu.harvard.hms.dbmi.avillach.hpds.processing.MissingConsentsException;
import edu.harvard.hms.dbmi.avillach.hpds.processing.v3.PartitionedPhenotypicObservationStore;
import edu.harvard.hms.dbmi.avillach.hpds.test.util.BuildIntegrationTestEnvironment;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(SpringExtension.class)
@EnableAutoConfiguration
// this test is specifically testing when authorization filters are required, which is not the default for integration tests
@SpringBootTest(
    classes = edu.harvard.hms.dbmi.avillach.hpds.service.HpdsApplication.class, properties = {"hpds.requireAuthorizationFilter=true"}
)
@ActiveProfiles("integration-test")
@AutoConfigureMockMvc
class PartitionedPhenotypicObservationStoreIntegrationTest {

    private static final Set<String> NO_CONSENTS = Set.of();

    private static final Set<String> GRANTED = Set.of("partition1");

    /** Consents for studies this node does not host, which a caller can legitimately hold. */
    private static final Set<String> OFF_NODE = Set.of("phs999999.c1");

    private static final String AGE = "\\open_access-1000Genomes\\data\\SYNTHETIC_AGE\\";

    private static final String SEX = "\\open_access-1000Genomes\\data\\SEX\\";

    @Autowired
    private PartitionedPhenotypicObservationStore partitionedPhenotypicObservationStore;

    @BeforeAll
    public static void beforeAll() {
        BuildIntegrationTestEnvironment instance = BuildIntegrationTestEnvironment.INSTANCE;
    }

    @Test
    public void getKeysForRange_noConsents_throwException() {
        assertThrows(MissingConsentsException.class, () -> {
            partitionedPhenotypicObservationStore.getKeysForRange("/a/concept/path/", 0.0, 10.0, NO_CONSENTS);
        });
    }

    @Test
    public void getKeysForValues_noConsents_throwException() {
        assertThrows(MissingConsentsException.class, () -> {
            partitionedPhenotypicObservationStore.getKeysForValues("/a/concept/path/", Set.of("value"), NO_CONSENTS);
        });
    }

    @Test
    public void getAllKeys_noConsents_throwException() {
        assertThrows(MissingConsentsException.class, () -> {
            partitionedPhenotypicObservationStore.getAllKeys("/a/concept/path/", NO_CONSENTS);
        });
    }

    @Test
    public void getCube_noConsents_throwException() {
        assertThrows(MissingConsentsException.class, () -> {
            partitionedPhenotypicObservationStore.getCube("/a/concept/path/", NO_CONSENTS);
        });
    }

    @Test
    public void getPatientIds_noConsents_throwException() {
        assertThrows(MissingConsentsException.class, () -> {
            partitionedPhenotypicObservationStore.getPatientIds(NO_CONSENTS);
        });
    }



    @Test
    public void getKeysForRange_hasConsents_doNotThrow() {
        partitionedPhenotypicObservationStore.getKeysForRange("\\open_access-1000Genomes\\data\\SYNTHETIC_AGE\\", 0.0, 10.0, GRANTED);
    }

    @Test
    public void getKeysForValues_hasConsents_doNotThrow() {
        partitionedPhenotypicObservationStore.getKeysForValues("\\open_access-1000Genomes\\data\\SEX\\", Set.of("male"), GRANTED);
    }

    @Test
    public void getAllKeys_hasConsents_doNotThrow() {
        partitionedPhenotypicObservationStore.getAllKeys("\\open_access-1000Genomes\\data\\SYNTHETIC_AGE\\", GRANTED);
    }

    @Test
    public void getCube_hasConsents_doNotThrow() {
        partitionedPhenotypicObservationStore.getCube("\\open_access-1000Genomes\\data\\SYNTHETIC_AGE\\", GRANTED);
    }

    @Test
    public void getPatientIds_hasConsents_doNotThrow() {

        Set<Integer> patientIds = partitionedPhenotypicObservationStore.getPatientIds(GRANTED);
        assertFalse(patientIds.isEmpty());
    }

    @Test
    public void getPatientIds_consentsMatchNoPartition_returnNoData() {
        assertTrue(partitionedPhenotypicObservationStore.getPatientIds(OFF_NODE).isEmpty());
    }

    @Test
    public void getCube_consentsMatchNoPartition_returnNoData() {
        assertTrue(partitionedPhenotypicObservationStore.getCube(AGE, OFF_NODE).isEmpty());
    }

    @Test
    public void getCube_conceptPathNotInAnyGrantedPartition_returnNoData() {
        assertTrue(partitionedPhenotypicObservationStore.getCube("\\no\\such\\concept\\", GRANTED).isEmpty());
    }
}
