package edu.harvard.hms.dbmi.avillach.operations;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import edu.harvard.hms.dbmi.avillach.operations.configuration.Configuration;
import edu.harvard.hms.dbmi.avillach.operations.configuration.ConfigurationRepository;
import edu.harvard.hms.dbmi.avillach.operations.dataset.NamedDatasetRepository;
import edu.harvard.hms.dbmi.avillach.operations.query.QueryRepository;
import edu.harvard.hms.dbmi.avillach.operations.query.SiteRepository;

/**
 * Smoke test: boots the full Spring context against H2 (see src/test/resources/application.yml) and proves that (1) the application context
 * loads at all -- including the security configuration (WebSecurityConfig, GatewayPrivilegesFilter) and the web MVC configuration
 * (GatewayUserArgumentResolver) -- and (2) the JPA entities and repositories, co-located under this application's base package, are
 * correctly detected and wired.
 */
@SpringBootTest
class OperationsApplicationTest {

    @Autowired
    private ConfigurationRepository configurationRepository;

    @Autowired
    private NamedDatasetRepository namedDatasetRepository;

    @Autowired
    private QueryRepository queryRepository;

    @Autowired
    private SiteRepository siteRepository;

    @Test
    void contextLoadsWithApiDataRepositoriesWired() {
        assertThat(configurationRepository).isNotNull();
        assertThat(namedDatasetRepository).isNotNull();
        assertThat(queryRepository).isNotNull();
        assertThat(siteRepository).isNotNull();
    }

    @Test
    @Transactional
    void apiDataRepositoriesCanRoundTripAgainstTheRealEntityTables() {
        // A real save -> reload round trip (not just a non-null bean / always-true count check) -- proves the
        // naming-strategy override and entity scan actually produced a working JPA mapping against the H2-backed
        // schema, not merely that Spring wired *some* proxy.
        String uniqueName = "smoke-test-" + UUID.randomUUID();
        Configuration saved = configurationRepository.save(new Configuration().setName(uniqueName).setKind("smoke-test"));

        Configuration reloaded = configurationRepository.findById(saved.getUuid()).orElseThrow();

        assertThat(reloaded.getName()).isEqualTo(uniqueName);
        assertThat(reloaded.getKind()).isEqualTo("smoke-test");
    }
}
