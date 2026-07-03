package edu.harvard.hms.dbmi.avillach.hpds.service.health;

import edu.harvard.hms.dbmi.avillach.hpds.data.phenotype.ColumnMeta;
import edu.harvard.hms.dbmi.avillach.hpds.processing.AbstractProcessor;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Status;

import java.util.Set;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class HpdsReadinessHealthIndicatorTest {

    @Test
    void upWhenPhenotypeDataLoaded() {
        AbstractProcessor proc = mock(AbstractProcessor.class);
        TreeMap<String, ColumnMeta> dict = new TreeMap<>();
        dict.put("\\demographics\\AGE\\", mock(ColumnMeta.class));
        when(proc.getDictionary()).thenReturn(dict);
        when(proc.getInfoStoreColumns()).thenReturn(Set.of());

        assertThat(new HpdsReadinessHealthIndicator(proc).health().getStatus()).isEqualTo(Status.UP);
    }

    @Test
    void upWhenGenomicDataLoaded() {
        AbstractProcessor proc = mock(AbstractProcessor.class);
        when(proc.getDictionary()).thenReturn(new TreeMap<>());
        when(proc.getInfoStoreColumns()).thenReturn(Set.of("Gene_with_variant"));

        assertThat(new HpdsReadinessHealthIndicator(proc).health().getStatus()).isEqualTo(Status.UP);
    }

    @Test
    void downWhenNoDataLoaded() {
        AbstractProcessor proc = mock(AbstractProcessor.class);
        when(proc.getDictionary()).thenReturn(new TreeMap<>());
        when(proc.getInfoStoreColumns()).thenReturn(Set.of());

        assertThat(new HpdsReadinessHealthIndicator(proc).health().getStatus()).isEqualTo(Status.DOWN);
    }
}
