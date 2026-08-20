package edu.harvard.hms.dbmi.avillach.hpds.service.health;

import edu.harvard.hms.dbmi.avillach.hpds.data.phenotype.SummaryColumnMeta;
import edu.harvard.hms.dbmi.avillach.hpds.processing.GenomicProcessor;
import edu.harvard.hms.dbmi.avillach.hpds.processing.v3.PartitionedPhenotypicObservationStore;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;

import java.util.Set;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HpdsReadinessHealthIndicatorTest {

    private static final String GENOMIC_IMPL = "localDistributed";

    private static PartitionedPhenotypicObservationStore observationStoreWith(TreeMap<String, SummaryColumnMeta> dictionary) {
        PartitionedPhenotypicObservationStore observationStore = mock(PartitionedPhenotypicObservationStore.class);
        when(observationStore.getMetaStore()).thenReturn(dictionary);
        return observationStore;
    }

    private static GenomicProcessor genomicProcessorWith(Set<String> infoStoreColumns) {
        GenomicProcessor genomicProcessor = mock(GenomicProcessor.class);
        when(genomicProcessor.getInfoStoreColumns()).thenReturn(infoStoreColumns);
        return genomicProcessor;
    }

    private static TreeMap<String, SummaryColumnMeta> dictionaryWithOneColumn() {
        TreeMap<String, SummaryColumnMeta> dict = new TreeMap<>();
        dict.put("\\demographics\\AGE\\", mock(SummaryColumnMeta.class));
        return dict;
    }

    @Test
    void upWhenPhenotypeDataLoaded() {
        PartitionedPhenotypicObservationStore observationStore = observationStoreWith(dictionaryWithOneColumn());
        GenomicProcessor genomicProcessor = genomicProcessorWith(Set.of());

        assertThat(new HpdsReadinessHealthIndicator(observationStore, genomicProcessor, GENOMIC_IMPL).health().getStatus()).isEqualTo(Status.UP);
    }

    @Test
    void upWhenGenomicDataLoaded() {
        PartitionedPhenotypicObservationStore observationStore = observationStoreWith(new TreeMap<>());
        GenomicProcessor genomicProcessor = genomicProcessorWith(Set.of("Gene_with_variant"));

        assertThat(new HpdsReadinessHealthIndicator(observationStore, genomicProcessor, GENOMIC_IMPL).health().getStatus()).isEqualTo(Status.UP);
    }

    @Test
    void downWhenGenomicEnabledButNoDataLoaded() {
        PartitionedPhenotypicObservationStore observationStore = observationStoreWith(new TreeMap<>());
        GenomicProcessor genomicProcessor = genomicProcessorWith(Set.of());

        assertThat(new HpdsReadinessHealthIndicator(observationStore, genomicProcessor, GENOMIC_IMPL).health().getStatus()).isEqualTo(Status.DOWN);
    }

    @Test
    void upWhenGenomicDisabledAndPhenotypeLoaded() {
        PartitionedPhenotypicObservationStore observationStore = observationStoreWith(dictionaryWithOneColumn());
        GenomicProcessor genomicProcessor = mock(GenomicProcessor.class);

        Health health = new HpdsReadinessHealthIndicator(observationStore, genomicProcessor, "").health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsEntry("genomicData", "not configured");
        // Genomic store must not be inspected when genomic support is not configured.
        verify(genomicProcessor, never()).getInfoStoreColumns();
    }

    @Test
    void downWhenGenomicDisabledAndNoPhenotypeLoaded() {
        PartitionedPhenotypicObservationStore observationStore = observationStoreWith(new TreeMap<>());
        GenomicProcessor genomicProcessor = mock(GenomicProcessor.class);

        assertThat(new HpdsReadinessHealthIndicator(observationStore, genomicProcessor, "").health().getStatus()).isEqualTo(Status.DOWN);
        verify(genomicProcessor, never()).getInfoStoreColumns();
    }
}
