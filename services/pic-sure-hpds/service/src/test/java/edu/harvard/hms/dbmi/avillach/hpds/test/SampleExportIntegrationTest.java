package edu.harvard.hms.dbmi.avillach.hpds.test;

import edu.harvard.hms.dbmi.avillach.hpds.data.phenotype.PhenoCube;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.ResultType;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.AuthorizationFilter;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.PhenotypicFilter;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.PhenotypicFilterType;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.Query;
import edu.harvard.hms.dbmi.avillach.hpds.processing.PhenotypeMetaStore;
import edu.harvard.hms.dbmi.avillach.hpds.processing.io.CsvWriter;
import edu.harvard.hms.dbmi.avillach.hpds.processing.v3.AsyncResult;
import edu.harvard.hms.dbmi.avillach.hpds.processing.v3.MultiValueQueryV3Processor;
import edu.harvard.hms.dbmi.avillach.hpds.processing.v3.PhenotypicObservationStore;
import edu.harvard.hms.dbmi.avillach.hpds.processing.v3.QueryExecutor;
import edu.harvard.hms.dbmi.avillach.hpds.processing.v3.SampleProperties;
import edu.harvard.hms.dbmi.avillach.hpds.test.util.BuildIntegrationTestEnvironment;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doReturn;

/**
 * Runs sample exports through the real {@link MultiValueQueryV3Processor}, {@link QueryExecutor}, phenotypic executor and sampler against
 * the integration dataset, and reads the exported patient ids back from the CSV the export wrote.
 *
 * <p>The integration dataset has no consents concept, so the observation store is a spy that serves one in-memory consents cube built from
 * the dataset's real patient ids. Every other concept, and every lookup the executor and the sampler make, goes to the real store.
 *
 * <p>The consent groups are laid out over the patient ids in ascending order: {@link #STUDY_ONE} holds every second patient,
 * {@link #STUDY_TWO} every third, so they overlap, and {@link #SMALL_STUDY} holds the first fifty.
 */
@ExtendWith(SpringExtension.class)
@EnableAutoConfiguration
@SpringBootTest(classes = edu.harvard.hms.dbmi.avillach.hpds.service.HpdsApplication.class)
@ActiveProfiles("integration-test")
public class SampleExportIntegrationTest {

    private static final String CONSENTS = AuthorizationFilter.CONSENTS_CONCEPT_PATH;
    private static final String SEX = "\\open_access-1000Genomes\\data\\SEX\\";
    private static final String STUDY_ONE = "phs000001.c1";
    private static final String STUDY_TWO = "phs000002.c1";
    private static final String SMALL_STUDY = "phs000003.c1";

    @Autowired
    private MultiValueQueryV3Processor processor;

    @Autowired
    private QueryExecutor queryExecutor;

    @Autowired
    private PhenotypeMetaStore phenotypeMetaStore;

    @Autowired
    private SampleProperties sampleProperties;

    @MockitoSpyBean
    private PhenotypicObservationStore phenotypicObservationStore;

    private TreeSet<Integer> studyOne;
    private TreeSet<Integer> studyTwo;
    private TreeSet<Integer> smallStudy;

    @BeforeAll
    public static void beforeAll() {
        BuildIntegrationTestEnvironment instance = BuildIntegrationTestEnvironment.INSTANCE;
    }

    @BeforeEach
    void serveConsents() {
        List<Integer> patients = List.copyOf(phenotypeMetaStore.getPatientIds());
        studyOne = new TreeSet<>();
        studyTwo = new TreeSet<>();
        smallStudy = new TreeSet<>(patients.subList(0, 50));
        for (int index = 0; index < patients.size(); index++) {
            if (index % 2 == 0) {
                studyOne.add(patients.get(index));
            }
            if (index % 3 == 0) {
                studyTwo.add(patients.get(index));
            }
        }
        TreeMap<String, TreeSet<Integer>> membersByConsent = new TreeMap<>();
        membersByConsent.put(STUDY_ONE, studyOne);
        membersByConsent.put(STUDY_TWO, studyTwo);
        membersByConsent.put(SMALL_STUDY, smallStudy);
        PhenoCube<String> consents = new PhenoCube<>(CONSENTS, String.class);
        consents.setCategoryMap(membersByConsent);
        doReturn(Optional.of(consents)).when(phenotypicObservationStore).getCube(CONSENTS);
    }

    @Test
    void sampleExportForOneConsentIsASmallerSubsetOfTheFullExport() throws IOException {
        int minSize = sampleProperties.getMinSize();
        Set<Integer> cohort = queryExecutor.getPatientSubsetForQuery(femaleParticipants(false, STUDY_ONE));

        Set<Integer> fullExport = exportedPatientIds(femaleParticipants(false, STUDY_ONE));
        Set<Integer> sampleExport = exportedPatientIds(femaleParticipants(true, STUDY_ONE));

        assertEquals(cohort, fullExport);
        assertTrue(fullExport.size() > minSize, "the cohort must be above sample.min-size for this test to mean anything");
        assertTrue(studyOne.containsAll(fullExport));
        assertFalse(studyOne.equals(fullExport), "the phenotypic filter must narrow the consent group");
        assertEquals(minSize, sampleExport.size());
        assertTrue(sampleExport.size() < fullExport.size());
        assertTrue(fullExport.containsAll(sampleExport));
        assertTrue(studyOne.containsAll(sampleExport));
        assertEquals(sampleExport, exportedPatientIds(femaleParticipants(true, STUDY_ONE)));
    }

    @Test
    void sampleExportForTwoConsentsStaysInsideThemAndDrawsFromBoth() throws IOException {
        Set<Integer> fullExport = exportedPatientIds(femaleParticipants(false, STUDY_ONE, STUDY_TWO));
        Set<Integer> sampleExport = exportedPatientIds(femaleParticipants(true, STUDY_ONE, STUDY_TWO));

        assertEquals(sampleProperties.getMinSize(), sampleExport.size());
        assertTrue(fullExport.containsAll(sampleExport));
        assertTrue(sampleExport.stream().allMatch(id -> studyOne.contains(id) || studyTwo.contains(id)));
        assertTrue(sampleExport.stream().anyMatch(studyOne::contains));
        assertTrue(sampleExport.stream().anyMatch(studyTwo::contains));
    }

    @Test
    void sampleExportOfACohortAtOrBelowMinSizeIsTheFullExport() throws IOException {
        Set<Integer> fullExport = exportedPatientIds(femaleParticipants(false, SMALL_STUDY));
        Set<Integer> sampleExport = exportedPatientIds(femaleParticipants(true, SMALL_STUDY));

        assertFalse(fullExport.isEmpty());
        assertTrue(fullExport.size() <= sampleProperties.getMinSize());
        assertTrue(smallStudy.containsAll(fullExport));
        assertEquals(fullExport, sampleExport);
    }

    private static Query femaleParticipants(boolean sample, String... consents) {
        return new Query(
            List.of(SEX), List.of(new AuthorizationFilter(CONSENTS, Set.of(consents))),
            new PhenotypicFilter(PhenotypicFilterType.FILTER, SEX, Set.of("female"), null, null, null), List.of(), ResultType.DATAFRAME,
            null, null, sample
        ).generateId();
    }

    private Set<Integer> exportedPatientIds(Query query) throws IOException {
        File csv = File.createTempFile("sample-export-" + System.nanoTime(), ".csv");
        csv.deleteOnExit();
        AsyncResult result = new AsyncResult(query, processor, new CsvWriter(csv));
        result.setId(query.id().toString());
        result.run();
        assertEquals(AsyncResult.Status.SUCCESS, result.getStatus());
        List<String> rows = Files.readAllLines(csv.toPath());
        assertEquals(MultiValueQueryV3Processor.PATIENT_ID_FIELD_NAME, rows.get(0).split(",", -1)[0]);
        Set<Integer> ids = rows.stream().skip(1).map(row -> Integer.valueOf(row.split(",", -1)[0])).collect(Collectors.toSet());
        assertEquals(rows.size() - 1, ids.size(), "the export holds one row per participant");
        return ids;
    }
}
