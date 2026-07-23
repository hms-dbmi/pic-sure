package edu.harvard.hms.dbmi.avillach.hpds.processing;

import edu.harvard.hms.dbmi.avillach.hpds.data.genotype.VariableVariantMasks;
import edu.harvard.hms.dbmi.avillach.hpds.data.genotype.VariantMask;
import edu.harvard.hms.dbmi.avillach.hpds.data.genotype.VariantMaskBitmaskImpl;
import edu.harvard.hms.dbmi.avillach.hpds.data.genotype.VariantMaskSparseImpl;
import edu.harvard.hms.dbmi.avillach.hpds.data.genotype.caching.VariantBucketHolder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import reactor.core.publisher.Mono;

import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith({MockitoExtension.class, OutputCaptureExtension.class})
public class GenomicProcessorPatientMergingParentImplTest {

    @Mock
    private GenomicProcessor mockProcessor1;
    @Mock
    private GenomicProcessor mockProcessor2;
    @Mock
    private GenomicProcessor mockProcessor3;

    private GenomicProcessorPatientMergingParentImpl patientMergingParent;

    @BeforeEach
    public void setup() {
        patientMergingParent = new GenomicProcessorPatientMergingParentImpl(List.of(mockProcessor1, mockProcessor2, mockProcessor3));
    }

    @Test
    public void getPatientMask_validResponses_returnMerged() {
        DistributableQuery distributableQuery = new DistributableQuery();
        when(mockProcessor1.getPatientMask(distributableQuery))
            .thenReturn(Mono.just(new VariantMaskBitmaskImpl(new BigInteger("11011011", 2))));
        when(mockProcessor1.getPatientIds()).thenReturn(List.of("1", "2", "3", "4"));
        when(mockProcessor2.getPatientMask(distributableQuery))
            .thenReturn(Mono.just(new VariantMaskBitmaskImpl(new BigInteger("110001100011", 2))));
        when(mockProcessor2.getPatientIds()).thenReturn(List.of("5", "6", "7", "8", "9", "10", "11", "12"));
        when(mockProcessor3.getPatientMask(distributableQuery))
            .thenReturn(Mono.just(new VariantMaskBitmaskImpl(new BigInteger("11000111", 2))));
        when(mockProcessor3.getPatientIds()).thenReturn(List.of("15", "16", "17", "18"));
        VariantMask patientMask = patientMergingParent.getPatientMask(distributableQuery).block();
        VariantMask expectedPatientMask = new VariantMaskBitmaskImpl(new BigInteger("11000100011000011011", 2));
        assertEquals(expectedPatientMask, patientMask);
    }

    @Test
    public void getPatientMask_noPatientResponses_returnMerged() {
        DistributableQuery distributableQuery = new DistributableQuery();
        when(mockProcessor1.getPatientMask(distributableQuery))
            .thenReturn(Mono.just(new VariantMaskBitmaskImpl(new BigInteger("11011011", 2))));
        when(mockProcessor1.getPatientIds()).thenReturn(List.of("1", "2", "3", "4"));
        when(mockProcessor2.getPatientMask(distributableQuery))
            .thenReturn(Mono.just(new VariantMaskBitmaskImpl(new BigInteger("110000000011", 2))));
        when(mockProcessor2.getPatientIds()).thenReturn(List.of("5", "6", "7", "8", "9", "10", "11", "12"));
        when(mockProcessor3.getPatientMask(distributableQuery))
            .thenReturn(Mono.just(new VariantMaskBitmaskImpl(new BigInteger("11000011", 2))));
        when(mockProcessor3.getPatientIds()).thenReturn(List.of("15", "16", "17", "18"));
        VariantMask patientMask = patientMergingParent.getPatientMask(distributableQuery).block();
        VariantMask expectedPatientMask = new VariantMaskBitmaskImpl(new BigInteger("11000000000000011011", 2));
        assertEquals(expectedPatientMask, patientMask);
    }

    @Test
    public void getPatientMask_emptyResponses_returnMerged() {
        DistributableQuery distributableQuery = new DistributableQuery();
        when(mockProcessor1.getPatientMask(distributableQuery))
            .thenReturn(Mono.just(new VariantMaskBitmaskImpl(new BigInteger("11011011", 2))));
        when(mockProcessor1.getPatientIds()).thenReturn(List.of("1", "2", "3", "4"));
        when(mockProcessor2.getPatientMask(distributableQuery))
            .thenReturn(Mono.just(new VariantMaskBitmaskImpl(new BigInteger("1111", 2))));
        when(mockProcessor2.getPatientIds()).thenReturn(List.of());
        when(mockProcessor3.getPatientMask(distributableQuery))
            .thenReturn(Mono.just(new VariantMaskBitmaskImpl(new BigInteger("11000111", 2))));
        when(mockProcessor3.getPatientIds()).thenReturn(List.of("5", "6", "7", "8"));
        VariantMask patientMask = patientMergingParent.getPatientMask(distributableQuery).block();
        VariantMask expectedPatientMask = new VariantMaskBitmaskImpl(new BigInteger("110001011011", 2));
        assertEquals(expectedPatientMask, patientMask);
    }

    @Test
    public void patientIdInit_validPatients_noException(CapturedOutput output) {
        when(mockProcessor1.getPatientIds()).thenReturn(List.of("1", "42", "99"));
        when(mockProcessor2.getPatientIds()).thenReturn(List.of("2", "50", "100"));
        when(mockProcessor3.getPatientIds()).thenReturn(List.of("1000", "10001", "1002", "1003"));

        patientMergingParent = new GenomicProcessorPatientMergingParentImpl(List.of(
                mockProcessor1, mockProcessor2, mockProcessor3
        ));

        assertFalse(output.getOut().contains("duplicate patients found in patient partitions"));
    }

    @Test
    public void patientIdInit_invalidPatients_warnMessage(CapturedOutput output) {
        when(mockProcessor1.getPatientIds()).thenReturn(List.of("1", "42", "99"));
        when(mockProcessor2.getPatientIds()).thenReturn(List.of("2", "42", "100"));
        when(mockProcessor3.getPatientIds()).thenReturn(List.of("1000", "10001", "1002", "1003"));

        patientMergingParent = new GenomicProcessorPatientMergingParentImpl(List.of(
                mockProcessor1, mockProcessor2, mockProcessor3
        ));

        assertTrue(output.getOut().contains("1 duplicate patients found in patient partitions"));
    }

    @Test
    public void patientIdInit_multipleInvalidPatients_warnMessage(CapturedOutput output) {
        when(mockProcessor1.getPatientIds()).thenReturn(List.of("1", "42", "99"));
        when(mockProcessor2.getPatientIds()).thenReturn(List.of("2", "50", "100"));
        when(mockProcessor3.getPatientIds()).thenReturn(List.of("1", "42", "99", "1003"));

        patientMergingParent = new GenomicProcessorPatientMergingParentImpl(List.of(
                mockProcessor1, mockProcessor2, mockProcessor3
        ));

        assertTrue(output.getOut().contains("3 duplicate patients found in patient partitions"));
    }


    @Test
    public void createMaskForPatientSet_validResponses_returnMerged() {
        Set<Integer> patientSubset = Set.of(2, 3, 8, 9, 15);
        when(mockProcessor1.createMaskForPatientSet(patientSubset)).thenReturn(new VariantMaskBitmaskImpl(new BigInteger("11011011", 2)));
        when(mockProcessor1.getPatientIds()).thenReturn(List.of("1", "2", "3", "4"));
        when(mockProcessor2.createMaskForPatientSet(patientSubset)).thenReturn(new VariantMaskSparseImpl(Set.of(3, 4)));
        when(mockProcessor2.getPatientIds()).thenReturn(List.of("5", "6", "7", "8", "9", "10", "11", "12"));
        when(mockProcessor3.createMaskForPatientSet(patientSubset)).thenReturn(new VariantMaskBitmaskImpl(new BigInteger("11000111", 2)));
        when(mockProcessor3.getPatientIds()).thenReturn(List.of("15", "16", "17", "18"));
        VariantMask patientMask = patientMergingParent.createMaskForPatientSet(patientSubset);
        VariantMask expectedPatientMask = new VariantMaskBitmaskImpl(new BigInteger("11000100011000011011", 2));
        assertEquals(expectedPatientMask, patientMask);
    }

    @Test
    public void createMaskForPatientSet_validResponsesOneEmpty_returnMerged() {
        Set<Integer> patientSubset = Set.of(2, 3, 15);
        when(mockProcessor1.createMaskForPatientSet(patientSubset)).thenReturn(new VariantMaskBitmaskImpl(new BigInteger("11011011", 2)));
        when(mockProcessor1.getPatientIds()).thenReturn(List.of("1", "2", "3", "4"));
        when(mockProcessor2.createMaskForPatientSet(patientSubset)).thenReturn(VariantMask.emptyInstance());
        when(mockProcessor2.getPatientIds()).thenReturn(List.of("5", "6", "7", "8", "9", "10", "11", "12"));
        when(mockProcessor3.createMaskForPatientSet(patientSubset)).thenReturn(new VariantMaskBitmaskImpl(new BigInteger("11000111", 2)));
        when(mockProcessor3.getPatientIds()).thenReturn(List.of("15", "16", "17", "18"));
        VariantMask patientMask = patientMergingParent.createMaskForPatientSet(patientSubset);
        VariantMask expectedPatientMask = new VariantMaskBitmaskImpl(new BigInteger("11000100000000011011", 2));
        assertEquals(expectedPatientMask, patientMask);
    }


    @Test
    public void getMasks_validEmptyResponses_returnEmpty() {
        String path = "chr21,5032061,A,Z,LOC102723996,missense_variant";
        when(mockProcessor1.getMasks(eq(path), any(VariantBucketHolder.class))).thenReturn(Optional.empty());
        when(mockProcessor2.getMasks(eq(path), any(VariantBucketHolder.class))).thenReturn(Optional.empty());
        when(mockProcessor3.getMasks(eq(path), any(VariantBucketHolder.class))).thenReturn(Optional.empty());

        Optional<VariableVariantMasks> masks = patientMergingParent.getMasks(path, new VariantBucketHolder<>());
        assertEquals(Optional.of(new VariableVariantMasks()), masks);
    }

    @Test
    public void getMasks_validEmptyAndNullResponses_returnEmpty() {
        String path = "chr21,5032061,A,Z,LOC102723996,missense_variant";
        when(mockProcessor1.getMasks(eq(path), any(VariantBucketHolder.class))).thenReturn(Optional.empty());
        when(mockProcessor2.getMasks(eq(path), any(VariantBucketHolder.class))).thenReturn(Optional.of(new VariableVariantMasks()));
        when(mockProcessor3.getMasks(eq(path), any(VariantBucketHolder.class))).thenReturn(Optional.empty());

        Optional<VariableVariantMasks> masks = patientMergingParent.getMasks(path, new VariantBucketHolder<>());
        assertEquals(Optional.of(new VariableVariantMasks()), masks);
    }


    @Test
    public void getMasks_validResponses_returnMerged() {
        String path = "chr21,5032061,A,Z,LOC102723996,missense_variant";
        VariableVariantMasks variableVariantMasks1 = new VariableVariantMasks();
        variableVariantMasks1.heterozygousMask = new VariantMaskBitmaskImpl(new BigInteger("11011011", 2));
        VariableVariantMasks variableVariantMasks2 = new VariableVariantMasks();
        variableVariantMasks2.heterozygousMask = new VariantMaskSparseImpl(Set.of(3, 4));
        VariableVariantMasks variableVariantMasks3 = new VariableVariantMasks();
        variableVariantMasks3.heterozygousMask = new VariantMaskBitmaskImpl(new BigInteger("11000111", 2));

        when(mockProcessor1.getPatientIds()).thenReturn(List.of("1", "2", "3", "4"));
        when(mockProcessor2.getPatientIds()).thenReturn(List.of("5", "6", "7", "8", "9", "10", "11", "12"));
        when(mockProcessor3.getPatientIds()).thenReturn(List.of("15", "16", "17", "18"));


        when(mockProcessor1.getMasks(eq(path), any(VariantBucketHolder.class))).thenReturn(Optional.of(variableVariantMasks1));
        when(mockProcessor2.getMasks(eq(path), any(VariantBucketHolder.class))).thenReturn(Optional.of(variableVariantMasks2));
        when(mockProcessor3.getMasks(eq(path), any(VariantBucketHolder.class))).thenReturn(Optional.of(variableVariantMasks3));

        Optional<VariableVariantMasks> masks = patientMergingParent.getMasks(path, new VariantBucketHolder<>());

        VariantMask expectedPatientMask = new VariantMaskBitmaskImpl(new BigInteger("11000100011000011011", 2));
        assertEquals(expectedPatientMask, masks.get().heterozygousMask);
    }

    @Test
    public void getMasks_validResponsesSinglePartition_returnResult() {
        String path = "chr21,5032061,A,Z,LOC102723996,missense_variant";
        VariableVariantMasks variableVariantMasks1 = new VariableVariantMasks();
        variableVariantMasks1.heterozygousMask = new VariantMaskBitmaskImpl(new BigInteger("11011011", 2));

        when(mockProcessor1.getPatientIds()).thenReturn(List.of("1", "2", "3", "4"));


        when(mockProcessor1.getMasks(eq(path), any(VariantBucketHolder.class))).thenReturn(Optional.of(variableVariantMasks1));

        Optional<VariableVariantMasks> masks =
            new GenomicProcessorPatientMergingParentImpl(List.of(mockProcessor1)).getMasks(path, new VariantBucketHolder<>());

        assertEquals(variableVariantMasks1.heterozygousMask, masks.get().heterozygousMask);
    }

    @Test
    public void getMasks_validResponsesSinglePartitionEmpty_returnEmpty() {
        String path = "chr21,5032061,A,Z,LOC102723996,missense_variant";

        when(mockProcessor1.getPatientIds()).thenReturn(List.of("1", "2", "3", "4"));
        when(mockProcessor1.getMasks(eq(path), any(VariantBucketHolder.class))).thenReturn(Optional.empty());

        Optional<VariableVariantMasks> masks =
            new GenomicProcessorPatientMergingParentImpl(List.of(mockProcessor1)).getMasks(path, new VariantBucketHolder<>());

        assertNull(masks.get().heterozygousMask);
    }

    @Test
    public void getMasks_validAndEmptyResponses_returnMerged() {
        String path = "chr21,5032061,A,Z,LOC102723996,missense_variant";
        VariableVariantMasks variableVariantMasks1 = new VariableVariantMasks();
        variableVariantMasks1.heterozygousMask = new VariantMaskBitmaskImpl(new BigInteger("11011011", 2));
        VariableVariantMasks variableVariantMasks3 = new VariableVariantMasks();
        variableVariantMasks3.heterozygousMask = new VariantMaskBitmaskImpl(new BigInteger("11000111", 2));

        when(mockProcessor1.getPatientIds()).thenReturn(List.of("1", "2", "3", "4"));
        when(mockProcessor2.getPatientIds()).thenReturn(List.of("5", "6", "7", "8", "9", "10", "11", "12"));
        when(mockProcessor3.getPatientIds()).thenReturn(List.of("15", "16", "17", "18"));


        when(mockProcessor1.getMasks(eq(path), any(VariantBucketHolder.class))).thenReturn(Optional.of(variableVariantMasks1));
        when(mockProcessor2.getMasks(eq(path), any(VariantBucketHolder.class))).thenReturn(Optional.empty());
        when(mockProcessor3.getMasks(eq(path), any(VariantBucketHolder.class))).thenReturn(Optional.of(variableVariantMasks3));

        Optional<VariableVariantMasks> masks = patientMergingParent.getMasks(path, new VariantBucketHolder<>());

        VariantMask expectedPatientMask = new VariantMaskBitmaskImpl(new BigInteger("11000100000000011011", 2));
        assertEquals(expectedPatientMask, masks.get().heterozygousMask);
    }

    @Test
    public void getMasks_validResponsesHomozygous_returnMerged() {
        String path = "chr21,5032061,A,Z,LOC102723996,missense_variant";
        VariableVariantMasks variableVariantMasks1 = new VariableVariantMasks();
        variableVariantMasks1.homozygousMask = new VariantMaskBitmaskImpl(new BigInteger("11011011", 2));
        VariableVariantMasks variableVariantMasks2 = new VariableVariantMasks();
        variableVariantMasks2.homozygousMask = new VariantMaskSparseImpl(Set.of(3, 4));
        VariableVariantMasks variableVariantMasks3 = new VariableVariantMasks();
        variableVariantMasks3.homozygousMask = new VariantMaskBitmaskImpl(new BigInteger("11000111", 2));

        when(mockProcessor1.getPatientIds()).thenReturn(List.of("1", "2", "3", "4"));
        when(mockProcessor2.getPatientIds()).thenReturn(List.of("5", "6", "7", "8", "9", "10", "11", "12"));
        when(mockProcessor3.getPatientIds()).thenReturn(List.of("15", "16", "17", "18"));


        when(mockProcessor1.getMasks(eq(path), any(VariantBucketHolder.class))).thenReturn(Optional.of(variableVariantMasks1));
        when(mockProcessor2.getMasks(eq(path), any(VariantBucketHolder.class))).thenReturn(Optional.of(variableVariantMasks2));
        when(mockProcessor3.getMasks(eq(path), any(VariantBucketHolder.class))).thenReturn(Optional.of(variableVariantMasks3));

        Optional<VariableVariantMasks> masks = patientMergingParent.getMasks(path, new VariantBucketHolder<>());

        VariantMask expectedPatientMask = new VariantMaskBitmaskImpl(new BigInteger("11000100011000011011", 2));
        assertEquals(expectedPatientMask, masks.get().homozygousMask);
        assertNull(masks.get().heterozygousMask);
        assertNull(masks.get().heterozygousNoCallMask);
        assertNull(masks.get().homozygousNoCallMask);
    }

    @Test
    public void getVariantList_oneNode_returnVariants() {
        DistributableQuery distributableQuery = new DistributableQuery();
        when(mockProcessor1.getVariantList(distributableQuery)).thenReturn(Mono.just(Set.of("variant1", "variant2")));

        patientMergingParent = new GenomicProcessorPatientMergingParentImpl(List.of(mockProcessor1));

        Set<String> variantList = patientMergingParent.getVariantList(distributableQuery).block();
        assertEquals(Set.of("variant1", "variant2"), variantList);
    }

    @Test
    public void getVariantMetadata_mixedEmptyVariants_mergedCorrectly() {
        List<String> variantList = List.of("variant1", "variant2", "variant3");
        when(mockProcessor1.getVariantMetadata(variantList)).thenReturn(Map.of());
        when(mockProcessor2.getVariantMetadata(variantList)).thenReturn(Map.of("variant1", Set.of("metadata1", "metadata2")));
        when(mockProcessor3.getVariantMetadata(variantList)).thenReturn(Map.of("variant3", Set.of("metadata31", "metadata32")));

        Map<String, Set<String>> variantMetadata = patientMergingParent.getVariantMetadata(variantList);
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

        Map<String, Set<String>> variantMetadata = patientMergingParent.getVariantMetadata(variantList);
        assertEquals(Set.of("metadata1", "metadata2", "metadata3"), variantMetadata.get("variant1"));
        assertEquals(Set.of("metadata31", "metadata32"), variantMetadata.get("variant3"));
        assertEquals(2, variantMetadata.size());
    }
}
