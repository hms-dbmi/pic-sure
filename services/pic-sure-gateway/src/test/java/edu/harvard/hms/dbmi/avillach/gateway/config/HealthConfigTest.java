package edu.harvard.hms.dbmi.avillach.gateway.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import edu.harvard.hms.dbmi.avillach.gateway.health.DownstreamHealthProperties;
import edu.harvard.hms.dbmi.avillach.gateway.health.SystemHealthService;

/** Proves HealthConfig registers the deep-health beans so the /system/status controller and the actuator composite can consume them. */
@SpringBootTest
class HealthConfigTest {

    @Autowired
    private SystemHealthService systemHealthService;

    @Autowired
    private DownstreamHealthProperties downstreamHealthProperties;

    @Test
    void registersHealthBeans() {
        assertThat(systemHealthService).isNotNull();
        assertThat(downstreamHealthProperties).isNotNull();
    }
}
