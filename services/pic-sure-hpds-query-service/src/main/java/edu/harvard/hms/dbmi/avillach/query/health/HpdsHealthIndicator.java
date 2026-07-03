package edu.harvard.hms.dbmi.avillach.query.health;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.actuate.health.Status;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import edu.harvard.hms.dbmi.avillach.query.config.HpdsProperties;

/**
 * Deep health for this DB-free service: there is no {@code DataSource} to probe (this module owns no database at all -- see the class
 * javadoc on {@code QueryService}), so the only real dependency worth reporting on is HPDS reachability. Probes each DISTINCT configured
 * backend base (auth/open -- in AIO they're typically the same URL and collapse to a single probe) with a short GET to
 * {@code {origin(base)}{healthPath}} -- HPDS exposes Actuator at the host root, not under the {@code /PIC-SURE} query context, so the probe
 * uses the base URL's origin ({@code scheme://authority}), not the full query base; {@code UP} only when every distinct base responds with
 * a 2xx. The gateway composes this service's own deep health into its aggregate view -- this indicator does not cascade into probing
 * anything beyond HPDS itself.
 *
 * <p>The probe client uses its OWN short, health-check-specific connect/read timeouts ({@link #HEALTH_CONNECT_TIMEOUT_SEC}/
 * {@link #HEALTH_READ_TIMEOUT_SEC}) rather than {@link HpdsProperties#getConnectTimeoutSec()}/{@link HpdsProperties#getReadTimeoutSec()} --
 * those are sized for real query traffic (default 300s read) and would let a black-holed HPDS hang {@code /actuator/health} (which the
 * gateway aggregates) for minutes. A health probe must fail fast and report {@code DOWN}, never hang.
 */
@Component("hpds")
public class HpdsHealthIndicator implements HealthIndicator {

    private static final int HEALTH_CONNECT_TIMEOUT_SEC = 2;
    private static final int HEALTH_READ_TIMEOUT_SEC = 3;

    private final HpdsProperties props;
    private final RestClient probe;

    @Autowired
    public HpdsHealthIndicator(HpdsProperties props, RestClient.Builder builder) {
        this.props = props;
        this.probe = builder.requestFactory(timeoutBoundRequestFactory()).build();
    }

    HpdsHealthIndicator(HpdsProperties props, RestClient probe) { // test constructor
        this.props = props;
        this.probe = probe;
    }

    /**
     * Mirrors {@code HpdsClientConfig}'s pooled-client timeout setup, but with short, health-specific values baked into the pooled
     * {@link CloseableHttpClient} itself (a {@link org.apache.hc.client5.http.io.HttpClientConnectionManager}'s
     * {@code defaultConnectionConfig} is where Apache HttpComponents 5 timeouts actually live -- the per-request setters on
     * {@link HttpComponentsClientHttpRequestFactory} are no-ops once a preconfigured {@code CloseableHttpClient} is supplied).
     */
    private static HttpComponentsClientHttpRequestFactory timeoutBoundRequestFactory() {
        PoolingHttpClientConnectionManager cm = new PoolingHttpClientConnectionManager();
        cm.setDefaultConnectionConfig(
            ConnectionConfig.custom().setConnectTimeout(HEALTH_CONNECT_TIMEOUT_SEC, TimeUnit.SECONDS)
                .setSocketTimeout(HEALTH_READ_TIMEOUT_SEC, TimeUnit.SECONDS).build()
        );
        CloseableHttpClient httpClient = HttpClients.custom().setConnectionManager(cm).build();
        return new HttpComponentsClientHttpRequestFactory(httpClient);
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
            String url = originOf(base) + props.getHealthPath();
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

    /**
     * HPDS serves its Actuator health at the host ROOT (e.g. {@code http://hpds:8080/actuator/health}), while the query API base carries
     * the {@code /PIC-SURE} context (e.g. {@code http://hpds:8080/PIC-SURE}). So the health probe targets the URL's ORIGIN
     * ({@code scheme://authority}) + {@code healthPath}, not the query base + {@code healthPath} (which would 404). Falls back to the raw
     * base if it can't be parsed as an absolute URL.
     */
    static String originOf(String base) {
        try {
            java.net.URI u = java.net.URI.create(base.trim());
            if (u.getScheme() != null && u.getAuthority() != null) {
                return u.getScheme() + "://" + u.getAuthority();
            }
        } catch (RuntimeException ignored) {
            // fall through to raw base
        }
        return base;
    }
}
