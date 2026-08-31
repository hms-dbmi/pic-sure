package edu.harvard.hms.dbmi.avillach.query.consent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import edu.harvard.hms.dbmi.avillach.commons.error.PicsureException;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.ResultType;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.AuthorizationFilter;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.GenomicFilter;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.PhenotypicFilter;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.PhenotypicFilterType;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.Query;

class ConsentFilterBuilderTest {

    private static final String CONSENT_PATH = "\\_consents\\";
    private static final String GENOMIC_CONSENT_PATH = "\\_topmed_consents\\";
    private static final String HARMONIZED_CONSENT_PATH = "\\_harmonized_consent\\";

    @Test
    void generalConsentsReplaceClientSuppliedAuthorizationFilters() {
        Query query = new Query(
            List.of(), List.of(new AuthorizationFilter("\\attacker_controlled\\", Set.of("grant"))), null, List.of(), ResultType.COUNT,
            null, null
        );

        Query scoped = new ConsentFilterBuilder().apply(query, Map.of(CONSENT_PATH, Set.of("phs001.c1")));

        assertThat(scoped.authorizationFilters()).containsExactly(new AuthorizationFilter(CONSENT_PATH, Set.of("phs001.c1")));
    }

    @Test
    void queryWithoutGeneralConsentIsDenied() {
        Query query = new Query(List.of(), List.of(), null, List.of(), ResultType.COUNT, null, null);

        assertThatThrownBy(() -> new ConsentFilterBuilder().apply(query, Map.of(GENOMIC_CONSENT_PATH, Set.of("phs002.c1"))))
            .isInstanceOfSatisfying(PicsureException.class, error -> {
                assertThat(error.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
                assertThat(error.getErrorType()).isEqualTo("consent_denied");
            });
    }

    @Test
    void genomicQueriesIncludeTopmedConsents() {
        Query query = new Query(
            List.of(), List.of(), null, List.of(new GenomicFilter("Gene_with_variant", List.of("APOE"), null, null)), ResultType.COUNT,
            null, null
        );

        Query scoped =
            new ConsentFilterBuilder().apply(query, Map.of(CONSENT_PATH, Set.of("phs001.c1"), GENOMIC_CONSENT_PATH, Set.of("phs002.c1")));

        assertThat(scoped.authorizationFilters()).containsExactlyInAnyOrder(
            new AuthorizationFilter(CONSENT_PATH, Set.of("phs001.c1")), new AuthorizationFilter(GENOMIC_CONSENT_PATH, Set.of("phs002.c1"))
        );
    }

    @Test
    void genomicQueriesWithoutTopmedConsentAreDenied() {
        Query query = new Query(
            List.of(), List.of(), null, List.of(new GenomicFilter("Gene_with_variant", List.of("APOE"), null, null)), ResultType.COUNT,
            null, null
        );

        assertThatThrownBy(() -> new ConsentFilterBuilder().apply(query, Map.of(CONSENT_PATH, Set.of("phs001.c1"))))
            .isInstanceOfSatisfying(PicsureException.class, error -> {
                assertThat(error.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
                assertThat(error.getErrorType()).isEqualTo("consent_denied");
            });
    }

    @Test
    void harmonizedQueriesIncludeHarmonizedConsents() {
        PhenotypicFilter filter = new PhenotypicFilter(
            PhenotypicFilterType.FILTER, "\\DCC Harmonized data set\\Demographics\\Age\\", Set.of("40"), null, null, null
        );
        Query query = new Query(List.of(), List.of(), filter, List.of(), ResultType.COUNT, null, null);

        Query scoped = new ConsentFilterBuilder()
            .apply(query, Map.of(CONSENT_PATH, Set.of("phs001.c1"), HARMONIZED_CONSENT_PATH, Set.of("phs003.c1")));

        assertThat(scoped.authorizationFilters()).containsExactlyInAnyOrder(
            new AuthorizationFilter(CONSENT_PATH, Set.of("phs001.c1")),
            new AuthorizationFilter(HARMONIZED_CONSENT_PATH, Set.of("phs003.c1"))
        );
    }

    @Test
    void harmonizedQueriesWithoutHarmonizedConsentAreDenied() {
        PhenotypicFilter filter = new PhenotypicFilter(
            PhenotypicFilterType.FILTER, "\\DCC Harmonized data set\\Demographics\\Age\\", Set.of("40"), null, null, null
        );
        Query query = new Query(List.of(), List.of(), filter, List.of(), ResultType.COUNT, null, null);

        assertThatThrownBy(() -> new ConsentFilterBuilder().apply(query, Map.of(CONSENT_PATH, Set.of("phs001.c1"))))
            .isInstanceOfSatisfying(PicsureException.class, error -> assertThat(error.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
    }
}
