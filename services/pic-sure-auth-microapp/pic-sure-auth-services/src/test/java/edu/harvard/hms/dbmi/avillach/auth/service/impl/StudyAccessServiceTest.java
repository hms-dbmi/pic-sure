package edu.harvard.hms.dbmi.avillach.auth.service.impl;

import edu.harvard.hms.dbmi.avillach.auth.model.fenceMapping.StudyMetaData;
import edu.harvard.hms.dbmi.avillach.auth.service.impl.authentication.FENCEAuthenticationService;
import edu.harvard.hms.dbmi.avillach.auth.utils.FenceMappingUtility;
import org.junit.Before;
import org.junit.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.when;

public class StudyAccessServiceTest {

    @InjectMocks
    private StudyAccessService studyAccessService;

    @Mock
    private RoleService roleService;

    @Mock
    private FenceMappingUtility fenceMappingUtility;

    @Before
    public void init() {
        MockitoAnnotations.initMocks(this);
    }


    @Test
    public void testAddStudyAccessWithBlankIdentifier() {
        String studyIdentifier = "";
        String status = studyAccessService.addStudyAccess(studyIdentifier);
        assertEquals("Error: Study identifier cannot be blank", status);
    }

    @Test
    public void testAddStudyAccess() {
        String studyIdentifier = "testStudy";
        StudyMetaData studyMetaData = new StudyMetaData();
        studyMetaData.setStudyIdentifier(studyIdentifier);
        studyMetaData.setConsentGroupCode("");

        when(fenceMappingUtility.getFENCEMapping()).thenReturn(Map.of(studyIdentifier, studyMetaData));
        when(roleService.upsertRole(null, "MANUAL_testStudy", "MANUAL_ role MANUAL_testStudy")).thenReturn(true);

        String status = studyAccessService.addStudyAccess(studyIdentifier);
        assertEquals("Role 'MANUAL_testStudy' successfully created", status);
    }

    @Test
    public void testAddStudyAccessWithConsent() {
        String studyIdentifier = "testStudy2.c2";
        StudyMetaData studyMetaData = new StudyMetaData();
        studyMetaData.setStudyIdentifier("testStudy2");
        studyMetaData.setConsentGroupCode("c2");

        when(fenceMappingUtility.getFENCEMapping()).thenReturn(Map.of(studyIdentifier, studyMetaData));
        when(roleService.upsertRole(null, "MANUAL_testStudy2_c2", "MANUAL_ role MANUAL_testStudy2_c2")).thenReturn(true);

        String status = studyAccessService.addStudyAccess(studyIdentifier);
        assertEquals("Role 'MANUAL_testStudy2_c2' successfully created", status);
    }
}