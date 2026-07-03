package edu.harvard.hms.dbmi.avillach.hpds.service.health;

import edu.harvard.hms.dbmi.avillach.hpds.processing.AbstractProcessor;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Deep readiness for HPDS (spec 3.8): UP only when phenotype OR genomic data is
 * actually loaded — a real "data ready" signal, not just port-up. HPDS has no
 * DataSource, so this replaces the built-in db indicator.
 */
@Component("hpdsReadiness")
public class HpdsReadinessHealthIndicator implements HealthIndicator {

    private final AbstractProcessor abstractProcessor;

    public HpdsReadinessHealthIndicator(AbstractProcessor abstractProcessor) {
        this.abstractProcessor = abstractProcessor;
    }

    @Override
    public Health health() {
        try {
            int phenotypeColumns = abstractProcessor.getDictionary().size();
            int genomicColumns = abstractProcessor.getInfoStoreColumns().size();
            if (phenotypeColumns > 0 || genomicColumns > 0) {
                return Health.up()
                    .withDetail("phenotypeColumns", phenotypeColumns)
                    .withDetail("genomicColumns", genomicColumns)
                    .build();
            }
            return Health.down().withDetail("reason", "no phenotype or genomic data loaded").build();
        } catch (Exception ex) {
            return Health.down(ex).build();
        }
    }
}
