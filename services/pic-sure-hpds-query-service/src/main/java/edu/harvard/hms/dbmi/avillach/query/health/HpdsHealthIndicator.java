package edu.harvard.hms.dbmi.avillach.query.health;

import java.util.LinkedHashSet;
import java.util.Set;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.actuate.health.Status;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import edu.harvard.hms.dbmi.avillach.query.config.HpdsProperties;

/**
 * Deep health for this DB-free service: there is no {@code DataSource} to probe (this module owns no database at all -- see the class
 * javadoc on {@code QueryService}), so the only real dependency worth reporting on is HPDS reachability. Probes each DISTINCT configured
 * backend base (auth/open -- in AIO they're typically the same URL and collapse to a single probe) with a short GET to
 * {@code {base}{healthPath}}; {@code UP} only when every distinct base responds with a 2xx. The gateway composes this service's own deep
 * health into its aggregate view -- this indicator does not cascade into probing anything beyond HPDS itself.
 */
@Component("hpds")
public class HpdsHealthIndicator implements HealthIndicator {

    private final HpdsProperties props;
    private final RestClient probe;

    @Autowired
    public HpdsHealthIndicator(HpdsProperties props, RestClient.Builder builder) {
        this.props = props;
        this.probe = builder.build();
    }

    HpdsHealthIndicator(HpdsProperties props, RestClient probe) { // test constructor
        this.props = props;
        this.probe = probe;
    }

    @Override
    public Health health() {
        Set<String> bases = new LinkedHashSet<>(); // dedup auth/open (collapse in AIO)
        if (props.getAuthUrl() != null) {
            bases.add(props.getAuthUrl());
        }
        if (props.getOpenUrl() != null) {
            bases.add(props.getOpenUrl());
        }

        Health.Builder result = Health.up();
        boolean down = false;
        for (String base : bases) {
            String url = base + props.getHealthPath();
            try {
                probe.get().uri(url).retrieve().toBodilessEntity(); // 2xx -> reachable
                result.withDetail(base, "UP");
            } catch (Exception e) {
                down = true;
                result.withDetail(base, "DOWN: " + e.getMessage());
            }
        }
        return down ? result.status(Status.DOWN).build() : result.build();
    }
}
