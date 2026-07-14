package edu.harvard.hms.dbmi.avillach.hpds.processing;

import edu.harvard.hms.dbmi.avillach.hpds.data.genotype.VariableVariantMasks;
import edu.harvard.hms.dbmi.avillach.hpds.data.genotype.VariantMask;
import edu.harvard.hms.dbmi.avillach.hpds.data.genotype.VariantMaskBitmaskImpl;
import edu.harvard.hms.dbmi.avillach.hpds.data.genotype.VariantMasks;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.context.event.annotation.BeforeTestClass;

import java.math.BigInteger;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class PatientVariantJoinHandlerTest {

    private VariantService variantService;

    private PatientVariantJoinHandler patientVariantJoinHandler;

    public static final String[] PATIENT_IDS = {"101", "102", "103", "104", "105", "106", "107", "108"};
    public static final Set<Integer> PATIENT_IDS_INTEGERS = Set.of(PATIENT_IDS).stream().map(Integer::parseInt).collect(Collectors.toSet());
    public static final String[] VARIANT_INDEX = {"16,61642243,A,T,ABC,consequence1", "16,61642252,A,G,ABC,consequence1", "16,61642256,C,T,ABC,consequence2", "16,61642257,G,A,ABC,consequence3", "16,61642258,G,A,ABC,consequence3", "16,61642259,G,A,ABC,consequence1", "16,61642260,G,A,ABC,consequence1", "16,61642261,G,A,ABC,consequence1"};

    public PatientVariantJoinHandlerTest(@Mock VariantService variantService) {
        this.variantService = variantService;
        patientVariantJoinHandler = new PatientVariantJoinHandler(variantService);
        when(variantService.getVariantIndex()).thenReturn(VARIANT_INDEX);
    }

    @Test
    public void getPatientIdsForIntersectionOfVariantSets_allPatientsMatchOneVariant() {
        VariantIndex intersectionOfInfoFilters = new SparseVariantIndex(Set.of(0, 2, 4));
        when(variantService.getPatientIds()).thenReturn(PATIENT_IDS);
        when(variantService.emptyBitmask()).thenReturn(emptyBitmask(PATIENT_IDS));

        VariantMask maskForAllPatients = new VariantMaskBitmaskImpl(patientVariantJoinHandler.createMaskForPatientSet(PATIENT_IDS_INTEGERS));
        VariantMask maskForNoPatients = VariantMask.emptyInstance();

        VariableVariantMasks variantMasks = new VariableVariantMasks();
        variantMasks.heterozygousMask = maskForAllPatients;
        VariableVariantMasks emptyVariantMasks = new VariableVariantMasks();
        emptyVariantMasks.heterozygousMask = maskForNoPatients;
        when(variantService.getMasks(eq(VARIANT_INDEX[0]), any())).thenReturn(Optional.of(variantMasks));
        when(variantService.getMasks(eq(VARIANT_INDEX[2]), any())).thenReturn(Optional.of(emptyVariantMasks));
        when(variantService.getMasks(eq(VARIANT_INDEX[4]), any())).thenReturn(Optional.of(emptyVariantMasks));

        Set<Integer> patientIdsForIntersectionOfVariantSets = patientVariantJoinHandler.getPatientIdsForIntersectionOfVariantSets(null, intersectionOfInfoFilters).patientMaskToPatientIdSet(List.of(PATIENT_IDS));
        // this should be all patients, as all patients match one of the variants
        assertEquals(PATIENT_IDS_INTEGERS, patientIdsForIntersectionOfVariantSets);
    }

    @Test
    public void getPatientIdsForIntersectionOfVariantSets_allPatientsMatchOneVariantWithNoVariantFound() {
        VariantIndex intersectionOfInfoFilters = new SparseVariantIndex(Set.of(0, 2, 4));
        when(variantService.getPatientIds()).thenReturn(PATIENT_IDS);
        when(variantService.emptyBitmask()).thenReturn(emptyBitmask(PATIENT_IDS));

        VariantMask maskForAllPatients = new VariantMaskBitmaskImpl(patientVariantJoinHandler.createMaskForPatientSet(PATIENT_IDS_INTEGERS));
        VariantMask maskForNoPatients = VariantMask.emptyInstance();

        VariableVariantMasks variantMasks = new VariableVariantMasks();
        variantMasks.heterozygousMask = maskForAllPatients;
        VariableVariantMasks emptyVariantMasks = new VariableVariantMasks();
        emptyVariantMasks.heterozygousMask = maskForNoPatients;
        when(variantService.getMasks(eq(VARIANT_INDEX[0]), any())).thenReturn(Optional.of(variantMasks));
        when(variantService.getMasks(eq(VARIANT_INDEX[2]), any())).thenReturn(Optional.empty());
        when(variantService.getMasks(eq(VARIANT_INDEX[4]), any())).thenReturn(Optional.empty());

        Set<Integer> patientIdsForIntersectionOfVariantSets = patientVariantJoinHandler.getPatientIdsForIntersectionOfVariantSets(null, intersectionOfInfoFilters).patientMaskToPatientIdSet(List.of(PATIENT_IDS));
        // this should be all patients, as all patients match one of the variants
        assertEquals(PATIENT_IDS_INTEGERS, patientIdsForIntersectionOfVariantSets);
    }

    @Test
    public void getPatientIdsForIntersectionOfVariantSets_noPatientsMatchVariants() {
        VariantIndex intersectionOfInfoFilters = new SparseVariantIndex(Set.of(0, 2, 4));
        when(variantService.getPatientIds()).thenReturn(PATIENT_IDS);
        when(variantService.emptyBitmask()).thenReturn(emptyBitmask(PATIENT_IDS));

        VariantMask maskForNoPatients = VariantMask.emptyInstance();
        VariableVariantMasks emptyVariantMasks = new VariableVariantMasks();
        emptyVariantMasks.heterozygousMask = maskForNoPatients;
        when(variantService.getMasks(eq(VARIANT_INDEX[0]), any())).thenReturn(Optional.of(emptyVariantMasks));
        when(variantService.getMasks(eq(VARIANT_INDEX[2]), any())).thenReturn(Optional.of(emptyVariantMasks));
        when(variantService.getMasks(eq(VARIANT_INDEX[4]), any())).thenReturn(Optional.of(emptyVariantMasks));

        Set<Integer> patientIdsForIntersectionOfVariantSets = patientVariantJoinHandler.getPatientIdsForIntersectionOfVariantSets(Set.of(), intersectionOfInfoFilters).patientMaskToPatientIdSet(List.of(PATIENT_IDS));
        // this should be empty because all variants masks have no matching patients
        assertEquals(Set.of(), patientIdsForIntersectionOfVariantSets);
    }

    @Test
    public void getPatientIdsForIntersectionOfVariantSets_somePatientsMatchVariants() {
        VariantIndex intersectionOfInfoFilters = new SparseVariantIndex(Set.of(0, 2, 4));
        when(variantService.getPatientIds()).thenReturn(PATIENT_IDS);
        when(variantService.emptyBitmask()).thenReturn(emptyBitmask(PATIENT_IDS));

        VariantMask maskForPatients1 = new VariantMaskBitmaskImpl(patientVariantJoinHandler.createMaskForPatientSet(Set.of(101, 103)));
        VariantMask maskForPatients2 = new VariantMaskBitmaskImpl(patientVariantJoinHandler.createMaskForPatientSet(Set.of(103, 105)));
        VariableVariantMasks variantMasks = new VariableVariantMasks();
        variantMasks.heterozygousMask = maskForPatients1;
        VariableVariantMasks variantMasks2 = new VariableVariantMasks();
        variantMasks2.heterozygousMask = maskForPatients2;
        when(variantService.getMasks(eq(VARIANT_INDEX[0]), any())).thenReturn(Optional.of(variantMasks));
        when(variantService.getMasks(eq(VARIANT_INDEX[2]), any())).thenReturn(Optional.of(variantMasks2));

        Set<Integer> patientIdsForIntersectionOfVariantSets = patientVariantJoinHandler.getPatientIdsForIntersectionOfVariantSets(null, intersectionOfInfoFilters).patientMaskToPatientIdSet(List.of(PATIENT_IDS));
        // this should be all patients who match at least one variant
        assertEquals(Set.of(101, 103, 105), patientIdsForIntersectionOfVariantSets);
    }

    @Test
    public void getPatientIdsForIntersectionOfVariantSets_noVariants() {
        VariantIndex intersectionOfInfoFilters = VariantIndex.empty();
        when(variantService.getPatientIds()).thenReturn(PATIENT_IDS);

        Set<Integer> patientIdsForIntersectionOfVariantSets = patientVariantJoinHandler.getPatientIdsForIntersectionOfVariantSets(null, intersectionOfInfoFilters).patientMaskToPatientIdSet(List.of(PATIENT_IDS));
        // this should be empty, as there are no variants
        assertEquals(Set.of(), patientIdsForIntersectionOfVariantSets);
    }

    @Test
    public void getPatientIdsForIntersectionOfVariantSets_patientSubsetPassed() {
        VariantIndex intersectionOfInfoFilters = new SparseVariantIndex(Set.of(0, 2, 4));
        when(variantService.getPatientIds()).thenReturn(PATIENT_IDS);
        when(variantService.emptyBitmask()).thenReturn(emptyBitmask(PATIENT_IDS));

        VariantMask maskForPatients1 = new VariantMaskBitmaskImpl(patientVariantJoinHandler.createMaskForPatientSet(Set.of(101, 103, 105)));
        VariantMask maskForPatients2 = new VariantMaskBitmaskImpl(patientVariantJoinHandler.createMaskForPatientSet(Set.of(103, 105, 107)));
        VariableVariantMasks variantMasks = new VariableVariantMasks();
        variantMasks.heterozygousMask = maskForPatients1;
        VariableVariantMasks variantMasks2 = new VariableVariantMasks();
        variantMasks2.heterozygousMask = maskForPatients2;
        when(variantService.getMasks(eq(VARIANT_INDEX[0]), any())).thenReturn(Optional.of(variantMasks));
        when(variantService.getMasks(eq(VARIANT_INDEX[2]), any())).thenReturn(Optional.of(variantMasks2));

        Set<Integer> patientIdsForIntersectionOfVariantSets = patientVariantJoinHandler.getPatientIdsForIntersectionOfVariantSets(Set.of(102, 103, 104, 105, 106), intersectionOfInfoFilters).patientMaskToPatientIdSet(List.of(PATIENT_IDS));
        // this should be the union of patients matching variants (101, 103, 105, 107), intersected with the patient subset parameter (103, 104, 105) which is (103, 105)
        assertEquals(Set.of(103, 105), patientIdsForIntersectionOfVariantSets);
    }

    public BigInteger emptyBitmask(String[] patientIds) {
        String emptyVariantMask = "";
        for (String patientId : patientIds) {
            emptyVariantMask = emptyVariantMask + "0";
        }
        return new BigInteger("11" + emptyVariantMask + "11", 2);
    }
}