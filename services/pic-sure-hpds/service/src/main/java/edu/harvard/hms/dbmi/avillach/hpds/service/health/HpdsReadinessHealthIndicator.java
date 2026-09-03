package edu.harvard.hms.dbmi.avillach.hpds.service.health;

import edu.harvard.hms.dbmi.avillach.hpds.processing.GenomicProcessor;
import edu.harvard.hms.dbmi.avillach.hpds.processing.PhenotypeMetaStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Deep readiness for HPDS: a real "data ready" signal rather than just port-up. HPDS has no DataSource, so this replaces the built-in db
 * indicator.
 *
 * <p>Reads the phenotype metadata and genomic stores directly. {@link PhenotypeMetaStore} is the shared metadata source of truth for v1,
 * v2, and v3 processors, and a {@link GenomicProcessor} bean is always present as a no-op fallback when genomic support is not configured.
 *
 * <p>Genomic data is optional — open-access and phenotype-only deployments run without it. When genomic support is not configured
 * ({@code hpds.genomicProcessor.impl} unset), readiness depends on phenotype data alone and the genomic store is not inspected. When it is
 * configured, either phenotype or genomic data being loaded is enough to report UP.
 */
@Component("hpdsReadiness")
public class HpdsReadinessHealthIndicator implements HealthIndicator {

    private final PhenotypeMetaStore phenotypeMetaStore;
    private final GenomicProcessor genomicProcessor;
    private final boolean genomicEnabled;

    public HpdsReadinessHealthIndicator(
        PhenotypeMetaStore phenotypeMetaStore, GenomicProcessor genomicProcessor,
        @Value("${hpds.genomicProcessor.impl:}") String genomicProcessorImpl
    ) {
        this.phenotypeMetaStore = phenotypeMetaStore;
        this.genomicProcessor = genomicProcessor;
        this.genomicEnabled = genomicProcessorImpl != null && !genomicProcessorImpl.isBlank();
    }

    @Override
    public Health health() {
        try {
            int phenotypeColumns = phenotypeMetaStore.getMetaStore().size();
            Health.Builder builder = Health.unknown().withDetail("phenotypeColumns", phenotypeColumns);

            boolean dataLoaded = phenotypeColumns > 0;
            if (genomicEnabled) {
                int genomicColumns = genomicProcessor.getInfoStoreColumns().size();
                builder.withDetail("genomicColumns", genomicColumns);
                dataLoaded = dataLoaded || genomicColumns > 0;
            } else {
                builder.withDetail("genomicData", "not configured");
            }

            String reason = genomicEnabled ? "no phenotype or genomic data loaded" : "no phenotype data loaded";
            return (dataLoaded ? builder.up() : builder.down().withDetail("reason", reason)).build();
        } catch (Exception ex) {
            return Health.down(ex).build();
        }
    }
}
