package edu.harvard.hms.dbmi.avillach.auth.service.impl.authorization;


import edu.harvard.hms.dbmi.avillach.auth.model.fenceMapping.StudyMetaData;
import edu.harvard.hms.dbmi.avillach.auth.model.ras.RasDbgapPermission;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

public class BdcConsentsBuilderTest {

    private static final Map<String, StudyMetaData> DEFAULT_FENCE_MAPPING = Map.of(
        "phs123.c1", new StudyMetaData().setHarmonized(false).setDataType("P"), "phs123.c2",
        new StudyMetaData().setHarmonized(false).setDataType("P"), "phs456.c1", new StudyMetaData().setHarmonized(true).setDataType("P"),
        "phs456.c2", new StudyMetaData().setHarmonized(true).setDataType("P"), "phs789.c1",
        new StudyMetaData().setHarmonized(false).setDataType("G"), "phs789.c2", new StudyMetaData().setHarmonized(false).setDataType("G"),
        "phs999.c1", new StudyMetaData().setHarmonized(true).setDataType("G"), "phs999.c2",
        new StudyMetaData().setHarmonized(true).setDataType("G"), "open_access-1000Genomes",
        new StudyMetaData().setHarmonized(false).setDataType("P").setStudyType("public"), "tutorial-biolincc_framingham",
        new StudyMetaData().setHarmonized(false).setDataType("P").setStudyType("public")
    );

    /**
     * A user with no dbGaP permissions at all must still receive every public study in {@code \_consents\}. This is the guarantee that
     * replaced {@code RoleService.getPublicAccessRoles()}: deleting those roles is only safe because this builder injects public studies
     * unconditionally. If this regresses, public studies silently disappear for every unauthorized user.
     */
    @Test
    public void createConsents_userWithNoDbgapPermissions_stillReceivesPublicStudies() {
        Set<String> userStudies = Set.of();
        Set<String> consents = new BdcConsentsBuilder(DEFAULT_FENCE_MAPPING, userStudies).createConsents();
        assertEquals(
            Set.of("open_access-1000Genomes", "tutorial-biolincc_framingham"), consents,
            "public studies must reach \\_consents\\ without any dbGaP permission, and no non-public study may leak in"
        );
    }

    @Test
    public void createConsents_noConsentsNoPublic_throwException() {
        Set<String> userStudies = Set.of();
        assertThrows(
            IllegalStateException.class,
            () -> new BdcConsentsBuilder(Map.of("phs123.c1", new StudyMetaData().setHarmonized(false).setDataType("P")), userStudies)
                .createConsents()
        );
    }

    @Test
    public void createConsents_oneNormalConsent() {
        Set<String> userStudies = Set.of("phs123.c1");
        Set<String> consents = new BdcConsentsBuilder(DEFAULT_FENCE_MAPPING, userStudies).createConsents();
        assertEquals(
            Set.of("phs123.c1", "open_access-1000Genomes", "tutorial-biolincc_framingham"), consents
        );
    }

    @Test
    public void createConsents_multipleNormalConsent() {
        Set<String> userStudies = Set.of("phs123.c1", "phs123.c2");
        Set<String> consents = new BdcConsentsBuilder(DEFAULT_FENCE_MAPPING, userStudies).createConsents();
        assertEquals(
            Set.of("phs123.c1", "phs123.c2", "open_access-1000Genomes", "tutorial-biolincc_framingham"),
            consents
        );
    }

    @Test
    public void createConsents_oneNormalConsentOneMissingConsent_ignoreMissingConsent() {
        Set<String> userStudies = Set.of("phs123.c1", "phs321.c1");
        Set<String> consents = new BdcConsentsBuilder(DEFAULT_FENCE_MAPPING, userStudies).createConsents();
        assertEquals(
            Set.of("phs123.c1", "open_access-1000Genomes", "tutorial-biolincc_framingham"), consents
        );
    }


    @Test
    public void createConsents_harmonizedConsentsOnly() {
        Set<String> userStudies = Set.of("phs456.c1", "phs456.c2");
        Set<String> consents = new BdcConsentsBuilder(DEFAULT_FENCE_MAPPING, userStudies).createConsents();
        assertEquals(
            Set.of("phs456.c1", "phs456.c2", "open_access-1000Genomes", "tutorial-biolincc_framingham"),
            consents
        );
    }

    @Test
    public void createConsents_multipleNormalAndHarmonizedConsents() {
        Set<String> userStudies = Set.of("phs123.c1", "phs123.c2", "phs456.c1");
        Set<String> consents = new BdcConsentsBuilder(DEFAULT_FENCE_MAPPING, userStudies).createConsents();
        assertEquals(
            Set.of("phs123.c1", "phs123.c2", "phs456.c1", "open_access-1000Genomes", "tutorial-biolincc_framingham"),
            consents
        );
    }


    @Test
    public void createConsents_genomicConsentsOnly() {
        Set<String> userStudies = Set.of("phs789.c1", "phs789.c2");
        Set<String> consents = new BdcConsentsBuilder(DEFAULT_FENCE_MAPPING, userStudies).createConsents();
        assertEquals(
            Set.of("phs789.c1", "phs789.c2", "open_access-1000Genomes", "tutorial-biolincc_framingham"),
            consents
        );
    }

    @Test
    public void createConsents_multipleNormalAndGenomicConsents() {
        Set<String> userStudies = Set.of("phs123.c1", "phs123.c2", "phs789.c1");
        Set<String> consents = new BdcConsentsBuilder(DEFAULT_FENCE_MAPPING, userStudies).createConsents();
        assertEquals(
            Set.of("phs123.c1", "phs123.c2", "phs789.c1", "open_access-1000Genomes", "tutorial-biolincc_framingham"),
            consents
        );
    }

    @Test
    public void createConsents_harmonizedGenomicConsentsOnly() {
        Set<String> userStudies = Set.of("phs999.c1", "phs999.c2");
        Set<String> consents = new BdcConsentsBuilder(DEFAULT_FENCE_MAPPING, userStudies).createConsents();
        assertEquals(
            Set.of("phs999.c1", "phs999.c2", "open_access-1000Genomes", "tutorial-biolincc_framingham"),
            consents
        );
    }

    @Test
    public void createConsents_multipleNormalAndHarmonizedGenomicConsents() {
        Set<String> userStudies = Set.of("phs123.c1", "phs123.c2", "phs999.c1");
        Set<String> consents = new BdcConsentsBuilder(DEFAULT_FENCE_MAPPING, userStudies).createConsents();
        assertEquals(
            Set.of("phs123.c1", "phs123.c2", "phs999.c1", "open_access-1000Genomes", "tutorial-biolincc_framingham"),
            consents
        );
    }

    @Test
    public void createConsents_publicGenomicStudy_shouldBeAddedToTopmedConsents() {
        HashMap<String, StudyMetaData> studyMetaData = new HashMap<>(DEFAULT_FENCE_MAPPING);
        studyMetaData.put("open_access-1000Genomes", new StudyMetaData().setHarmonized(false).setDataType("P/G").setStudyType("public"));
        Set<String> userStudies = Set.of();
        Set<String> consents = new BdcConsentsBuilder(studyMetaData, userStudies).createConsents();
        assertEquals(consents, Set.of("open_access-1000Genomes", "tutorial-biolincc_framingham"));
    }
}
