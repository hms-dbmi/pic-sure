package edu.harvard.hms.dbmi.avillach.gateway.health;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

class SystemStatusControllerTest {

    /** Counts how many times the underlying aggregate check runs, to prove throttling. */
    static class CountingHealthService extends SystemHealthService {
        final AtomicInteger calls = new AtomicInteger();
        volatile boolean running = true;

        CountingHealthService() {
            super(new DownstreamHealthProperties(List.of(), 1000, 2000, null));
        }

        @Override
        public AggregateHealth check() {
            calls.incrementAndGet();
            return new AggregateHealth(running, List.of());
        }
    }

    @Test
    void mapsRunningAndDegradedStrings() {
        CountingHealthService svc = new CountingHealthService();
        SystemStatusController controller = new SystemStatusController(svc, 60_000L);

        svc.running = true;
        assertThat(controller.status()).isEqualTo("RUNNING");

        controller.expireCacheForTest();
        svc.running = false;
        assertThat(controller.status()).isEqualTo("ONE OR MORE COMPONENTS DEGRADED");
    }

    @Test
    void throttlesRepeatedCallsToOnePerWindow() {
        CountingHealthService svc = new CountingHealthService();
        SystemStatusController controller = new SystemStatusController(svc, 60_000L);

        controller.status();
        controller.status();
        controller.status();

        assertThat(svc.calls.get()).isEqualTo(1);
    }
}
