package edu.harvard.hms.dbmi.avillach.hpds.processing;

import edu.harvard.hms.dbmi.avillach.hpds.data.genotype.VariantMask;
import edu.harvard.hms.dbmi.avillach.hpds.data.genotype.VariantMaskBitmaskImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;

import java.math.BigInteger;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GenomicProcessorParentImplTest {
    @Mock
    private GenomicProcessor mockProcessor1;
    @Mock
    private GenomicProcessor mockProcessor2;
    @Mock
    private GenomicProcessor mockProcessor3;

    private GenomicProcessorParentImpl parentProcessor;

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
        when(mockProcessor3.getPatientIds()).thenReturn(List.of("1", "42", "99"));

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
        parentProcessor = new GenomicProcessorParentImpl(List.of(
                mockProcessor1, mockProcessor2, mockProcessor3
        ));

        VariantMask patientMask = parentProcessor.getPatientMask(distributableQuery).block();
        VariantMask expectedPatientMask = new VariantMaskBitmaskImpl(new BigInteger("110111100111", 2));
        assertEquals(expectedPatientMask, patientMask);
    }
}