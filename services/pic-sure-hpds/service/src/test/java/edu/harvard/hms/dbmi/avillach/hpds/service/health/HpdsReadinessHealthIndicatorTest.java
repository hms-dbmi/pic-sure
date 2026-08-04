package edu.harvard.hms.dbmi.avillach.hpds.service.health;

import edu.harvard.hms.dbmi.avillach.hpds.data.phenotype.ColumnMeta;
import edu.harvard.hms.dbmi.avillach.hpds.processing.GenomicProcessor;
import edu.harvard.hms.dbmi.avillach.hpds.processing.PhenotypeMetaStore;
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

    private static PhenotypeMetaStore metaStoreWith(TreeMap<String, ColumnMeta> dictionary) {
        PhenotypeMetaStore metaStore = mock(PhenotypeMetaStore.class);
        when(metaStore.getMetaStore()).thenReturn(dictionary);
        return metaStore;
    }

    private static GenomicProcessor genomicProcessorWith(Set<String> infoStoreColumns) {
        GenomicProcessor genomicProcessor = mock(GenomicProcessor.class);
        when(genomicProcessor.getInfoStoreColumns()).thenReturn(infoStoreColumns);
        return genomicProcessor;
    }

    private static TreeMap<String, ColumnMeta> dictionaryWithOneColumn() {
        TreeMap<String, ColumnMeta> dict = new TreeMap<>();
        dict.put("\\demographics\\AGE\\", mock(ColumnMeta.class));
        return dict;
    }

    @Test
    void upWhenPhenotypeDataLoaded() {
        PhenotypeMetaStore metaStore = metaStoreWith(dictionaryWithOneColumn());
        GenomicProcessor genomicProcessor = genomicProcessorWith(Set.of());

        assertThat(new HpdsReadinessHealthIndicator(metaStore, genomicProcessor, GENOMIC_IMPL).health().getStatus()).isEqualTo(Status.UP);
    }

    @Test
    void upWhenGenomicDataLoaded() {
        PhenotypeMetaStore metaStore = metaStoreWith(new TreeMap<>());
        GenomicProcessor genomicProcessor = genomicProcessorWith(Set.of("Gene_with_variant"));

        assertThat(new HpdsReadinessHealthIndicator(metaStore, genomicProcessor, GENOMIC_IMPL).health().getStatus()).isEqualTo(Status.UP);
    }

    @Test
    void downWhenGenomicEnabledButNoDataLoaded() {
        PhenotypeMetaStore metaStore = metaStoreWith(new TreeMap<>());
        GenomicProcessor genomicProcessor = genomicProcessorWith(Set.of());

        assertThat(new HpdsReadinessHealthIndicator(metaStore, genomicProcessor, GENOMIC_IMPL).health().getStatus()).isEqualTo(Status.DOWN);
    }

    @Test
    void upWhenGenomicDisabledAndPhenotypeLoaded() {
        PhenotypeMetaStore metaStore = metaStoreWith(dictionaryWithOneColumn());
        GenomicProcessor genomicProcessor = mock(GenomicProcessor.class);

        Health health = new HpdsReadinessHealthIndicator(metaStore, genomicProcessor, "").health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsEntry("genomicData", "not configured");
        // Genomic store must not be inspected when genomic support is not configured.
        verify(genomicProcessor, never()).getInfoStoreColumns();
    }

    @Test
    void downWhenGenomicDisabledAndNoPhenotypeLoaded() {
        PhenotypeMetaStore metaStore = metaStoreWith(new TreeMap<>());
        GenomicProcessor genomicProcessor = mock(GenomicProcessor.class);

        assertThat(new HpdsReadinessHealthIndicator(metaStore, genomicProcessor, "").health().getStatus()).isEqualTo(Status.DOWN);
        verify(genomicProcessor, never()).getInfoStoreColumns();
    }
}
