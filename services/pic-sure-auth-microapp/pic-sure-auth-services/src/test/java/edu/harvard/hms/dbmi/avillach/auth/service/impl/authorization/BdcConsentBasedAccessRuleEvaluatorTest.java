package edu.harvard.hms.dbmi.avillach.auth.service.impl.authorization;

import edu.harvard.hms.dbmi.avillach.auth.entity.UserConsents;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.ResultType;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class BdcConsentBasedAccessRuleEvaluatorTest {


    private BdcConsentBasedAccessRuleEvaluator bdcConsentBasedAccessRuleEvaluator;

    @BeforeEach
    public void setup() {
        bdcConsentBasedAccessRuleEvaluator = new BdcConsentBasedAccessRuleEvaluator();
    }

    @Test
    public void evaluateAccessRule_validPhenotypicFilters_accept() {
        UserConsents userConsents = new UserConsents().setConsents(Map.of("\\_consents\\", Set.of("phs123.c1", "phs456.c2")));
        Query query = new Query(
            List.of(), List.of(),
            new PhenotypicSubquery(
                null,
                List.of(
                    new PhenotypicFilter(PhenotypicFilterType.FILTER, "\\phs123\\data\\age\\", null, 30.0, 40.0, null),
                    new PhenotypicFilter(PhenotypicFilterType.FILTER, "\\phs456\\data\\age\\", null, 30.0, 40.0, null),
                    new PhenotypicFilter(PhenotypicFilterType.FILTER, "\\phs123\\data\\sex\\", Set.of("male", "female"), null, null, null)
                ), Operator.AND
            ), null, ResultType.COUNT, null, null
        );

        boolean result = bdcConsentBasedAccessRuleEvaluator.evaluateAccessRule(query, null, userConsents);
        assertTrue(result);
    }

    @Test
    public void evaluateAccessRule_phenotypicFiltersOneWrongConsent_reject() {
        UserConsents userConsents = new UserConsents().setConsents(Map.of("\\_consents\\", Set.of("phs123.c1", "phs456.c2")));
        Query query = new Query(
                List.of(), List.of(),
                new PhenotypicSubquery(
                        null,
                        List.of(
                                new PhenotypicFilter(PhenotypicFilterType.FILTER, "\\phs123\\data\\age\\", null, 30.0, 40.0, null),
                                new PhenotypicFilter(PhenotypicFilterType.FILTER, "\\phs456\\data\\age\\", null, 30.0, 40.0, null),
                                new PhenotypicFilter(PhenotypicFilterType.FILTER, "\\phs789\\data\\sex\\", Set.of("male", "female"), null, null, null)
                        ), Operator.AND
                ), null, ResultType.COUNT, null, null
        );

        boolean result = bdcConsentBasedAccessRuleEvaluator.evaluateAccessRule(query, null, userConsents);
        assertFalse(result);
    }

    @Test
    public void evaluateAccessRule_nestedInvalidPhenotypicFiltersConsent_reject() {
        UserConsents userConsents = new UserConsents().setConsents(Map.of("\\_consents\\", Set.of("phs123.c1", "phs456.c2")));
        Query query = new Query(
                List.of(), List.of(),
                new PhenotypicSubquery(
                        null,
                        List.of(
                                new PhenotypicFilter(PhenotypicFilterType.FILTER, "\\phs123\\data\\age\\", null, 30.0, 40.0, null),
                                new PhenotypicFilter(PhenotypicFilterType.FILTER, "\\phs456\\data\\age\\", null, 30.0, 40.0, null),
                                new PhenotypicSubquery(false, List.of(new PhenotypicFilter(PhenotypicFilterType.FILTER, "\\phs789\\data\\sex\\", Set.of("male", "female"), null, null, null)), Operator.AND)
                        ), Operator.AND
                ), null, ResultType.COUNT, null, null
        );

        boolean result = bdcConsentBasedAccessRuleEvaluator.evaluateAccessRule(query, null, userConsents);
        assertFalse(result);
    }


    @Test
    public void evaluateAccessRule_userNoConsents_reject() {
        UserConsents userConsents = new UserConsents().setConsents(Map.of());
        Query query = new Query(
            List.of(), List.of(),
            new PhenotypicSubquery(
                null,
                List.of(
                    new PhenotypicFilter(PhenotypicFilterType.FILTER, "\\phs123\\data\\age\\", null, 30.0, 40.0, null),
                    new PhenotypicFilter(PhenotypicFilterType.FILTER, "\\phs456\\data\\age\\", null, 30.0, 40.0, null),
                    new PhenotypicFilter(PhenotypicFilterType.FILTER, "\\phs123\\data\\sex\\", Set.of("male", "female"), null, null, null)
                ), Operator.AND
            ), null, ResultType.COUNT, null, null
        );

        boolean result = bdcConsentBasedAccessRuleEvaluator.evaluateAccessRule(query, null, userConsents);
        assertFalse(result);
    }

    @Test
    public void evaluateAccessRule_genomicFiltersValidTopmedConsents_accept() {
        Map<String, Set<String>> consents =
            Map.of("\\_consents\\", Set.of("phs123.c1", "phs456.c2"), "\\_topmed_consents\\", Set.of("phs123.c1", "phs456.c2"));
        UserConsents userConsents = new UserConsents().setConsents(consents);
        Query query = new Query(
            List.of(), List.of(),
            new PhenotypicSubquery(
                null,
                List.of(
                    new PhenotypicFilter(PhenotypicFilterType.FILTER, "\\phs123\\data\\age\\", null, 30.0, 40.0, null),
                    new PhenotypicFilter(PhenotypicFilterType.FILTER, "\\phs456\\data\\age\\", null, 30.0, 40.0, null),
                    new PhenotypicFilter(PhenotypicFilterType.FILTER, "\\phs123\\data\\sex\\", Set.of("male", "female"), null, null, null)
                ), Operator.AND
            ), List.of(new GenomicFilter("Gene_with_variant", List.of("CDH8"), null, null)), ResultType.COUNT, null, null
        );

        boolean result = bdcConsentBasedAccessRuleEvaluator.evaluateAccessRule(query, null, userConsents);
        assertTrue(result);
    }


    @Test
    public void evaluateAccessRule_genomicFiltersNoTopmedConsents_reject() {
        UserConsents userConsents = new UserConsents().setConsents(Map.of("\\_consents\\", Set.of("phs123.c1", "phs456.c2")));
        Query query = new Query(
            List.of(), List.of(),
            new PhenotypicSubquery(
                null,
                List.of(
                    new PhenotypicFilter(PhenotypicFilterType.FILTER, "\\phs123\\data\\age\\", null, 30.0, 40.0, null),
                    new PhenotypicFilter(PhenotypicFilterType.FILTER, "\\phs456\\data\\age\\", null, 30.0, 40.0, null),
                    new PhenotypicFilter(PhenotypicFilterType.FILTER, "\\phs123\\data\\sex\\", Set.of("male", "female"), null, null, null)
                ), Operator.AND
            ), List.of(new GenomicFilter("Gene_with_variant", List.of("CDH8"), null, null)), ResultType.COUNT, null, null
        );

        boolean result = bdcConsentBasedAccessRuleEvaluator.evaluateAccessRule(query, null, userConsents);
        assertFalse(result);
    }


    @Test
    public void evaluateAccessRule_validHarmonizedPhenotypicFilters_accept() {
        UserConsents userConsents = new UserConsents().setConsents(Map.of("\\_consents\\", Set.of("phs123.c1", "phs456.c2"), "\\_harmonized_consent\\", Set.of("phs789.c1", "phs789.c2")));
        Query query = new Query(
                List.of(), List.of(),
                new PhenotypicSubquery(
                        null,
                        List.of(
                                new PhenotypicFilter(PhenotypicFilterType.FILTER, "\\phs123\\data\\age\\", null, 30.0, 40.0, null),
                                new PhenotypicFilter(PhenotypicFilterType.FILTER, "\\DCC Harmonized data set\\data\\age\\", null, 30.0, 40.0, null),
                                new PhenotypicSubquery(false, List.of(new PhenotypicFilter(PhenotypicFilterType.FILTER, "\\DCC Harmonized data set\\data\\sex\\", Set.of("male", "female"), null, null, null)), Operator.AND)
                        ), Operator.AND
                ), null, ResultType.COUNT, null, null
        );

        boolean result = bdcConsentBasedAccessRuleEvaluator.evaluateAccessRule(query, null, userConsents);
        assertTrue(result);
    }

    @Test
    public void evaluateAccessRule_invalidNestedHarmonizedPhenotypicFilters_reject() {
        UserConsents userConsents = new UserConsents().setConsents(Map.of("\\_consents\\", Set.of("phs123.c1", "phs456.c2")));
        Query query = new Query(
                List.of(), List.of(),
                new PhenotypicSubquery(
                        null,
                        List.of(
                                new PhenotypicFilter(PhenotypicFilterType.FILTER, "\\phs123\\data\\age\\", null, 30.0, 40.0, null),
                                new PhenotypicFilter(PhenotypicFilterType.FILTER, "\\phs456\\data\\age\\", null, 30.0, 40.0, null),
                                new PhenotypicSubquery(false, List.of(new PhenotypicFilter(PhenotypicFilterType.FILTER, "\\DCC Harmonized data set\\data\\sex\\", Set.of("male", "female"), null, null, null)), Operator.AND)
                        ), Operator.AND
                ), null, ResultType.COUNT, null, null
        );

        boolean result = bdcConsentBasedAccessRuleEvaluator.evaluateAccessRule(query, null, userConsents);
        assertFalse(result);
    }

    @Test
    public void evaluateAccessRule_harmonizedPhenotypicFiltersEmptyUserConsent_reject() {
        UserConsents userConsents = new UserConsents().setConsents(Map.of("\\_consents\\", Set.of("phs123.c1", "phs456.c2"), "\\_harmonized_consent\\", Set.of()));
        Query query = new Query(
                List.of(), List.of(),
                new PhenotypicSubquery(
                        null,
                        List.of(
                                new PhenotypicFilter(PhenotypicFilterType.FILTER, "\\phs123\\data\\age\\", null, 30.0, 40.0, null),
                                new PhenotypicFilter(PhenotypicFilterType.FILTER, "\\phs456\\data\\age\\", null, 30.0, 40.0, null),
                                new PhenotypicFilter(PhenotypicFilterType.FILTER, "\\DCC Harmonized data set\\data\\sex\\", Set.of("male", "female"), null, null, null)
                        ), Operator.AND
                ), null, ResultType.COUNT, null, null
        );

        boolean result = bdcConsentBasedAccessRuleEvaluator.evaluateAccessRule(query, null, userConsents);
        assertFalse(result);
    }

    @Test
    public void setAuthorizationFiltersForQuery_normalConsents_addOnlyConsents() {
        UserConsents userConsents = new UserConsents().setConsents(Map.of("\\_consents\\", Set.of("phs123.c1", "phs456.c2"), "\\_harmonized_consent\\", Set.of("phs789.c1", "phs789.c2")));
        Query query = new Query(
                List.of(), List.of(),
                new PhenotypicSubquery(
                        null,
                        List.of(
                                new PhenotypicFilter(PhenotypicFilterType.FILTER, "\\phs123\\data\\age\\", null, 30.0, 40.0, null),
                                new PhenotypicFilter(PhenotypicFilterType.FILTER, "\\phs456\\data\\age\\", null, 30.0, 40.0, null)
                        ), Operator.AND
                ), null, ResultType.COUNT, null, null
        );

        Query queryWithConsents = bdcConsentBasedAccessRuleEvaluator.setAuthorizationFiltersForQuery(userConsents, query);
        assertEquals(1, queryWithConsents.authorizationFilters().size());
        assertEquals(new AuthorizationFilter("\\_consents\\", Set.of("phs123.c1", "phs456.c2")), queryWithConsents.authorizationFilters().get(0));
    }

    @Test
    public void evaluateAccessRule_filterIncludesTopmedAndParentStudyId_accept() {
        UserConsents userConsents = new UserConsents().setConsents(Map.of("\\_consents\\", Set.of("phs123.c1", "phs456.c2")));
        Query query = new Query(
                List.of(), List.of(),
                new PhenotypicSubquery(
                        null,
                        List.of(
                                new PhenotypicFilter(PhenotypicFilterType.FILTER, "\\phs123\\data\\age\\", null, 30.0, 40.0, null),
                                new PhenotypicFilter(PhenotypicFilterType.FILTER, "\\phs456\\data\\age\\", null, 30.0, 40.0, null),
                                new PhenotypicFilter(PhenotypicFilterType.FILTER, "\\_Topmed Study Accession with Subject ID\\", Set.of("phs789"), null, null, null),
                                new PhenotypicFilter(PhenotypicFilterType.FILTER, "\\_Parent Study Accession with Subject ID\\", Set.of("phs999"), null, null, null)
                        ), Operator.AND
                ), null, ResultType.COUNT, null, null
        );

        boolean result = bdcConsentBasedAccessRuleEvaluator.evaluateAccessRule(query, null, userConsents);
        assertTrue(result);
    }

    @Test
    public void evaluateAccessRule_selectIncludesTopmedAndParentStudyId_accept() {
        UserConsents userConsents = new UserConsents().setConsents(Map.of("\\_consents\\", Set.of("phs123.c1", "phs456.c2")));
        Query query = new Query(
                List.of("\\_Topmed Study Accession with Subject ID\\", "\\_Parent Study Accession with Subject ID\\", "\\_consents\\", "\\_harmonized_consent\\", "\\_topmed_consents\\"), List.of(),
                new PhenotypicSubquery(
                        null,
                        List.of(
                                new PhenotypicFilter(PhenotypicFilterType.FILTER, "\\phs123\\data\\age\\", null, 30.0, 40.0, null),
                                new PhenotypicFilter(PhenotypicFilterType.FILTER, "\\phs456\\data\\age\\", null, 30.0, 40.0, null),
                                new PhenotypicFilter(PhenotypicFilterType.FILTER, "\\phs123\\data\\sex\\", Set.of("male", "female"), null, null, null)
                        ), Operator.AND
                ), null, ResultType.COUNT, null, null
        );

        boolean result = bdcConsentBasedAccessRuleEvaluator.evaluateAccessRule(query, null, userConsents);
        assertTrue(result);
    }
}
