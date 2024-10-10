package edu.harvard.hms.dbmi.avillach.hpds.processing;

import edu.harvard.hms.dbmi.avillach.hpds.data.genotype.VariantMask;
import edu.harvard.hms.dbmi.avillach.hpds.data.genotype.VariantMaskBitmaskImpl;
import edu.harvard.hms.dbmi.avillach.hpds.data.genotype.VariantMaskSparseImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;

import java.math.BigInteger;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class GenomicProcessorParentImplTest {
    @Mock
    private GenomicProcessor mockProcessor1;
    @Mock
    private GenomicProcessor mockProcessor2;
    @Mock
    private GenomicProcessor mockProcessor3;

    private GenomicProcessorParentImpl parentProcessor;

    @BeforeEach
    public void setup() {
        parentProcessor = new GenomicProcessorParentImpl(List.of(
                mockProcessor1, mockProcessor2, mockProcessor3
        ));
    }

    @Test
    public void patientIdInit_patientsMatch_noException() {
        when(mockProcessor1.getPatientIds()).thenReturn(List.of("1", "42", "99"));
        when(mockProcessor2.getPatientIds()).thenReturn(List.of("1", "42", "99"));
        when(mockProcessor3.getPatientIds()).thenReturn(List.of("1", "42", "99"));
        parentProcessor = new GenomicProcessorParentImpl(List.of(
                mockProcessor1, mockProcessor2, mockProcessor3
        ));
    }
    @Test
    public void patientIdInit_patientsDiffer_exception() {
        when(mockProcessor1.getPatientIds()).thenReturn(List.of("1", "42", "99"));
        when(mockProcessor2.getPatientIds()).thenReturn(List.of("1", "43", "99"));

        assertThrows(IllegalStateException.class, () -> {
            parentProcessor = new GenomicProcessorParentImpl(List.of(
                    mockProcessor1, mockProcessor2, mockProcessor3
            ));
        });
    }


    @Test
    public void getPatientMask_validResponses_returnMerged() {
        DistributableQuery distributableQuery = new DistributableQuery();
        when(mockProcessor1.getPatientMask(distributableQuery)).thenReturn(Mono.just(new VariantMaskBitmaskImpl(new BigInteger("110110000011", 2))));
        when(mockProcessor2.getPatientMask(distributableQuery)).thenReturn(Mono.just(new VariantMaskBitmaskImpl(new BigInteger("110001100011", 2))));
        when(mockProcessor3.getPatientMask(distributableQuery)).thenReturn(Mono.just(new VariantMaskBitmaskImpl(new BigInteger("110000000111", 2))));

        VariantMask patientMask = parentProcessor.getPatientMask(distributableQuery).block();
        VariantMask expectedPatientMask = new VariantMaskBitmaskImpl(new BigInteger("110111100111", 2));
        assertEquals(expectedPatientMask, patientMask);
    }
    @Test
    public void getPatientMask_oneNode_returnPatients() {
        DistributableQuery distributableQuery = new DistributableQuery();
        when(mockProcessor1.getPatientMask(distributableQuery)).thenReturn(Mono.just(new VariantMaskBitmaskImpl(new BigInteger("110110000011", 2))));
        parentProcessor = new GenomicProcessorParentImpl(List.of(mockProcessor1));

        VariantMask patientMask = parentProcessor.getPatientMask(distributableQuery).block();
        VariantMask expectedPatientMask = new VariantMaskBitmaskImpl(new BigInteger("110110000011", 2));
        assertEquals(expectedPatientMask, patientMask);
    }

    @Test
    public void createMaskForPatientSet_oneNode_returnFirst() {
        Set<Integer> patientSet = Set.of(7, 8, 9);
        when(mockProcessor1.createMaskForPatientSet(patientSet)).thenReturn(new VariantMaskBitmaskImpl(new BigInteger("110100000011", 2)));

        parentProcessor = new GenomicProcessorParentImpl(List.of(mockProcessor1));

        VariantMask patientMask = parentProcessor.createMaskForPatientSet(patientSet);
        VariantMask expectedPatientMask = new VariantMaskBitmaskImpl(new BigInteger("110100000011", 2));
        assertEquals(expectedPatientMask, patientMask);
    }

    @Test
    public void createMaskForPatientSet_multipleNodes_returnFirst() {
        Set<Integer> patientSet = Set.of(7, 8, 9);
        when(mockProcessor1.createMaskForPatientSet(patientSet)).thenReturn(new VariantMaskBitmaskImpl(new BigInteger("110100000011", 2)));

        VariantMask patientMask = parentProcessor.createMaskForPatientSet(patientSet);
        VariantMask expectedPatientMask = new VariantMaskBitmaskImpl(new BigInteger("110100000011", 2));
        assertEquals(expectedPatientMask, patientMask);
        // this should just call the first node, since all nodes have identical patient sets
        verify(mockProcessor2, never()).createMaskForPatientSet(any());
        verify(mockProcessor3, never()).createMaskForPatientSet(any());
    }

    @Test
    public void getVariantList_overlappingVariants_mergeCorrectly() {
        DistributableQuery distributableQuery = new DistributableQuery();
        when(mockProcessor1.getVariantList(distributableQuery)).thenReturn(Mono.just(Set.of("variant1", "variant2")));
        when(mockProcessor2.getVariantList(distributableQuery)).thenReturn(Mono.just(Set.of("variant2", "variant3")));
        when(mockProcessor3.getVariantList(distributableQuery)).thenReturn(Mono.just(Set.of("variant3", "variant4")));

        Set<String> variantList = parentProcessor.getVariantList(distributableQuery).block();
        assertEquals(Set.of("variant1", "variant2", "variant3", "variant4"), variantList);
    }

    @Test
    public void getVariantList_oneNode_returnVariants() {
        DistributableQuery distributableQuery = new DistributableQuery();
        when(mockProcessor1.getVariantList(distributableQuery)).thenReturn(Mono.just(Set.of("variant1", "variant2")));

        parentProcessor = new GenomicProcessorParentImpl(List.of(mockProcessor1));

        Set<String> variantList = parentProcessor.getVariantList(distributableQuery).block();
        assertEquals(Set.of("variant1", "variant2"), variantList);
    }

    @Test
    public void getVariantMetadata_mixedEmptyVariants_mergedCorrectly() {
        List<String> variantList = List.of("variant1", "variant2", "variant3");
        when(mockProcessor1.getVariantMetadata(variantList)).thenReturn(Map.of());
        when(mockProcessor2.getVariantMetadata(variantList)).thenReturn(Map.of("variant1", Set.of("metadata1", "metadata2")));
        when(mockProcessor3.getVariantMetadata(variantList)).thenReturn(Map.of("variant3", Set.of("metadata31", "metadata32")));

        Map<String, Set<String>> variantMetadata = parentProcessor.getVariantMetadata(variantList);
        assertEquals(Set.of("metadata1", "metadata2"), variantMetadata.get("variant1"));
        assertEquals(Set.of("metadata31", "metadata32"), variantMetadata.get("variant3"));
        assertEquals(2, variantMetadata.size());
    }

    @Test
    public void getVariantMetadata_overlappingVariants_mergedCorrectly() {
        List<String> variantList = List.of("variant1", "variant2", "variant3");
        when(mockProcessor1.getVariantMetadata(variantList)).thenReturn(Map.of("variant1", Set.of("metadata1", "metadata2")));
        when(mockProcessor2.getVariantMetadata(variantList)).thenReturn(Map.of("variant1", Set.of("metadata1", "metadata3")));
        when(mockProcessor3.getVariantMetadata(variantList)).thenReturn(Map.of("variant3", Set.of("metadata31", "metadata32")));

        Map<String, Set<String>> variantMetadata = parentProcessor.getVariantMetadata(variantList);
        assertEquals(Set.of("metadata1", "metadata2", "metadata3"), variantMetadata.get("variant1"));
        assertEquals(Set.of("metadata31", "metadata32"), variantMetadata.get("variant3"));
        assertEquals(2, variantMetadata.size());
    }
}