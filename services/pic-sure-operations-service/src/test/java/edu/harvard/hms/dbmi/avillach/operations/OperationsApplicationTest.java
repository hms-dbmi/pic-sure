package edu.harvard.hms.dbmi.avillach.operations;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import edu.harvard.hms.dbmi.avillach.data.repository.ConfigurationRepository;
import edu.harvard.hms.dbmi.avillach.data.repository.NamedDatasetRepository;
import edu.harvard.hms.dbmi.avillach.data.repository.QueryRepository;
import edu.harvard.hms.dbmi.avillach.data.repository.SiteRepository;

/**
 * Smoke test: boots the full Spring context against H2 (see src/test/resources/application.yml) and proves that (1) the application context
 * loads at all -- including the security configuration (WebSecurityConfig, GatewayPrivilegesFilter) and the web MVC configuration
 * (GatewayUserArgumentResolver) -- and (2) the jakarta pic-sure-api-data repositories, which live outside this application's base package,
 * are correctly wired via the
 * @EntityScan/@EnableJpaRepositories declarations on {@link OperationsApplication}.
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
    void apiDataRepositoriesCanRoundTripAgainstTheRealEntityTables() {
        // A trivial, real DB round trip (not just a non-null bean check) -- proves the naming-strategy override and
        // entity scan actually produced working JPA repositories against the H2-backed schema, not merely that
        // Spring wired *some* proxy.
        long before = configurationRepository.count();
        assertThat(before).isGreaterThanOrEqualTo(0);
    }
}
