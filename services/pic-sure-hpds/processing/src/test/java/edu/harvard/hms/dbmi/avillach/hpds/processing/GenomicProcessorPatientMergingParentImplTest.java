package edu.harvard.hms.dbmi.avillach.hpds.processing;

import edu.harvard.hms.dbmi.avillach.hpds.data.genotype.VariantMask;
import edu.harvard.hms.dbmi.avillach.hpds.data.genotype.VariantMaskBitmaskImpl;
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

import static org.junit.jupiter.api.Assertions.*;
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
        patientMergingParent = new GenomicProcessorPatientMergingParentImpl(List.of(
                mockProcessor1, mockProcessor2, mockProcessor3
        ));
    }

    @Test
    public void getPatientMask_validResponses_returnMerged() {
        DistributableQuery distributableQuery = new DistributableQuery();
        when(mockProcessor1.getPatientMask(distributableQuery)).thenReturn(Mono.just(new VariantMaskBitmaskImpl(new BigInteger("11011011", 2))));
        when(mockProcessor1.getPatientIds()).thenReturn(List.of("1", "2", "3", "4"));
        when(mockProcessor2.getPatientMask(distributableQuery)).thenReturn(Mono.just(new VariantMaskBitmaskImpl(new BigInteger("110001100011", 2))));
        when(mockProcessor2.getPatientIds()).thenReturn(List.of("5", "6", "7", "8", "9", "10", "11", "12"));
        when(mockProcessor3.getPatientMask(distributableQuery)).thenReturn(Mono.just(new VariantMaskBitmaskImpl(new BigInteger("11000111", 2))));
        when(mockProcessor3.getPatientIds()).thenReturn(List.of("15", "16", "17", "18"));
        VariantMask patientMask = patientMergingParent.getPatientMask(distributableQuery).block();
        VariantMask expectedPatientMask = new VariantMaskBitmaskImpl(new BigInteger("11000100011000011011", 2));
        assertEquals(expectedPatientMask, patientMask);
    }

    @Test
    public void getPatientMask_noPatientResponses_returnMerged() {
        DistributableQuery distributableQuery = new DistributableQuery();
        when(mockProcessor1.getPatientMask(distributableQuery)).thenReturn(Mono.just(new VariantMaskBitmaskImpl(new BigInteger("11011011", 2))));
        when(mockProcessor1.getPatientIds()).thenReturn(List.of("1", "2", "3", "4"));
        when(mockProcessor2.getPatientMask(distributableQuery)).thenReturn(Mono.just(new VariantMaskBitmaskImpl(new BigInteger("110000000011", 2))));
        when(mockProcessor2.getPatientIds()).thenReturn(List.of("5", "6", "7", "8", "9", "10", "11", "12"));
        when(mockProcessor3.getPatientMask(distributableQuery)).thenReturn(Mono.just(new VariantMaskBitmaskImpl(new BigInteger("11000011", 2))));
        when(mockProcessor3.getPatientIds()).thenReturn(List.of("15", "16", "17", "18"));
        VariantMask patientMask = patientMergingParent.getPatientMask(distributableQuery).block();
        VariantMask expectedPatientMask = new VariantMaskBitmaskImpl(new BigInteger("11000000000000011011", 2));
        assertEquals(expectedPatientMask, patientMask);
    }

    @Test
    public void getPatientMask_emptyResponses_returnMerged() {
        DistributableQuery distributableQuery = new DistributableQuery();
        when(mockProcessor1.getPatientMask(distributableQuery)).thenReturn(Mono.just(new VariantMaskBitmaskImpl(new BigInteger("11011011", 2))));
        when(mockProcessor1.getPatientIds()).thenReturn(List.of("1", "2", "3", "4"));
        when(mockProcessor2.getPatientMask(distributableQuery)).thenReturn(Mono.just(new VariantMaskBitmaskImpl(new BigInteger("1111", 2))));
        when(mockProcessor2.getPatientIds()).thenReturn(List.of());
        when(mockProcessor3.getPatientMask(distributableQuery)).thenReturn(Mono.just(new VariantMaskBitmaskImpl(new BigInteger("11000111", 2))));
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
}