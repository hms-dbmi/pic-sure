package edu.harvard.hms.dbmi.avillach.auth.service.impl.authorization;

import static org.junit.jupiter.api.Assertions.assertEquals;

import edu.harvard.hms.dbmi.avillach.auth.entity.UserConsents;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.ResultType;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.AuthorizationFilter;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.GenomicFilter;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.Operator;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.PhenotypicFilter;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.PhenotypicFilterType;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.PhenotypicSubquery;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.Query;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class BdcConsentBasedAccessRuleEvaluatorTest {

    private static final String CONSENT_PATH = "\\_consents\\";
    private static final String GENOMIC_CONSENT_PATH = "\\_topmed_consents\\";
    private static final String HARMONIZED_CONSENT_PATH = "\\_harmonized_consent\\";
    private BdcConsentBasedAccessRuleEvaluator evaluator;

    @BeforeEach
    void setUp() {
        evaluator = new BdcConsentBasedAccessRuleEvaluator();
    }

    @Test
    void normalQueryIncludesOnlyGeneralConsents() {
        Query scoped = evaluator.setAuthorizationFiltersForQuery(consents(), query("\\phs123\\data\\age\\", List.of()));

        assertEquals(List.of(new AuthorizationFilter(CONSENT_PATH, Set.of("phs123.c1"))), scoped.authorizationFilters());
    }

    @Test
    void genomicQueryIncludesGenomicConsents() {
        GenomicFilter genomicFilter = new GenomicFilter("Gene_with_variant", List.of("CDH8"), null, null);

        Query scoped = evaluator.setAuthorizationFiltersForQuery(consents(), query("\\phs123\\data\\age\\", List.of(genomicFilter)));

        assertEquals(
            Set.of(
                new AuthorizationFilter(CONSENT_PATH, Set.of("phs123.c1")),
                new AuthorizationFilter(GENOMIC_CONSENT_PATH, Set.of("phs123.c1"))
            ), Set.copyOf(scoped.authorizationFilters())
        );
    }

    @Test
    void harmonizedQueryIncludesHarmonizedConsents() {
        Query scoped = evaluator.setAuthorizationFiltersForQuery(consents(), query("\\DCC Harmonized data set\\data\\age\\", List.of()));

        assertEquals(
            Set.of(
                new AuthorizationFilter(CONSENT_PATH, Set.of("phs123.c1")),
                new AuthorizationFilter(HARMONIZED_CONSENT_PATH, Set.of("phs789.c1"))
            ), Set.copyOf(scoped.authorizationFilters())
        );
    }

    private static UserConsents consents() {
        return new UserConsents().setConsents(
            Map.of(
                CONSENT_PATH, Set.of("phs123.c1"), GENOMIC_CONSENT_PATH, Set.of("phs123.c1"), HARMONIZED_CONSENT_PATH, Set.of("phs789.c1")
            )
        );
    }

    private static Query query(String conceptPath, List<GenomicFilter> genomicFilters) {
        PhenotypicFilter filter = new PhenotypicFilter(PhenotypicFilterType.FILTER, conceptPath, null, 30.0, 40.0, null);
        return new Query(
            List.of(), List.of(), new PhenotypicSubquery(null, List.of(filter), Operator.AND), genomicFilters, ResultType.COUNT, null, null
        );
    }
}
