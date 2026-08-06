package edu.harvard.hms.dbmi.avillach.hpds.test;

import edu.harvard.hms.dbmi.avillach.hpds.processing.MissingConsentsException;
import edu.harvard.hms.dbmi.avillach.hpds.processing.util.UserRequestContext;
import edu.harvard.hms.dbmi.avillach.hpds.processing.v3.PartitionedPhenotypicObservationStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

@ExtendWith(SpringExtension.class)
@EnableAutoConfiguration
@SpringBootTest(
    classes = edu.harvard.hms.dbmi.avillach.hpds.service.HpdsApplication.class, properties = {"hpds.requireAuthorizationFilter=true"}
)
@ActiveProfiles("integration-test")
@AutoConfigureMockMvc
class PartitionedPhenotypicObservationStoreIntegrationTest {

    @MockitoBean
    private UserRequestContext userRequestContext;

    @Autowired
    private PartitionedPhenotypicObservationStore partitionedPhenotypicObservationStore;

    @Test
    public void getKeysForRange_noConsents_throwException() {
        when(userRequestContext.getUserConsents()).thenReturn(List.of());

        assertThrows(MissingConsentsException.class, () -> {
            partitionedPhenotypicObservationStore.getKeysForRange("/a/concept/path/", 0.0, 10.0);
        });
    }

    @Test
    public void getKeysForValues_noConsents_throwException() {
        when(userRequestContext.getUserConsents()).thenReturn(List.of());

        assertThrows(MissingConsentsException.class, () -> {
            partitionedPhenotypicObservationStore.getKeysForValues("/a/concept/path/", Set.of("value"));
        });
    }

    @Test
    public void getAllKeys_noConsents_throwException() {
        when(userRequestContext.getUserConsents()).thenReturn(List.of());

        assertThrows(MissingConsentsException.class, () -> {
            partitionedPhenotypicObservationStore.getAllKeys("/a/concept/path/");
        });
    }

    @Test
    public void getCube_noConsents_throwException() {
        when(userRequestContext.getUserConsents()).thenReturn(List.of());

        assertThrows(MissingConsentsException.class, () -> {
            partitionedPhenotypicObservationStore.getCube("/a/concept/path/");
        });
    }

    @Test
    public void getPatientIds_noConsents_throwException() {
        when(userRequestContext.getUserConsents()).thenReturn(List.of());

        assertThrows(MissingConsentsException.class, () -> {
            partitionedPhenotypicObservationStore.getPatientIds();
        });
    }



    @Test
    public void getKeysForRange_hasConsents_doNotThrow() {
        when(userRequestContext.getUserConsents()).thenReturn(List.of("partition1"));
        partitionedPhenotypicObservationStore.getKeysForRange("\\open_access-1000Genomes\\data\\SYNTHETIC_AGE\\", 0.0, 10.0);
    }

    @Test
    public void getKeysForValues_hasConsents_doNotThrow() {
        when(userRequestContext.getUserConsents()).thenReturn(List.of("partition1"));
        partitionedPhenotypicObservationStore.getKeysForValues("\\open_access-1000Genomes\\data\\SEX\\", Set.of("male"));
    }

    @Test
    public void getAllKeys_hasConsents_doNotThrow() {
        when(userRequestContext.getUserConsents()).thenReturn(List.of("partition1"));
        partitionedPhenotypicObservationStore.getAllKeys("\\open_access-1000Genomes\\data\\SYNTHETIC_AGE\\");
    }

    @Test
    public void getCube_hasConsents_doNotThrow() {
        when(userRequestContext.getUserConsents()).thenReturn(List.of("partition1"));
        partitionedPhenotypicObservationStore.getCube("\\open_access-1000Genomes\\data\\SYNTHETIC_AGE\\");
    }

    @Test
    public void getPatientIds_hasConsents_doNotThrow() {
        when(userRequestContext.getUserConsents()).thenReturn(List.of("partition1"));

        Set<Integer> patientIds = partitionedPhenotypicObservationStore.getPatientIds();
        assertTrue(patientIds.size() > 0);
    }
}
