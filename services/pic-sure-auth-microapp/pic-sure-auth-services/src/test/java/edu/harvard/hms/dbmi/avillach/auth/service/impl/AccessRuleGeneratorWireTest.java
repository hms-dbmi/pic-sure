package edu.harvard.hms.dbmi.avillach.auth.service.impl;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.JsonPath;

import edu.harvard.dbmi.avillach.contracts.auth.TargetedRequest;
import edu.harvard.hms.dbmi.avillach.auth.entity.AccessRule;
import edu.harvard.hms.dbmi.avillach.auth.repository.AccessRuleRepository;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.ResultType;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.AuthorizationFilter;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.GenomicFilter;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.Operator;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.PhenotypicClause;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.PhenotypicFilter;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.PhenotypicFilterType;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.PhenotypicSubquery;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.Query;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * SECURITY: PSAMA does not only EVALUATE access rules, it MINTS them -- FENCE/study onboarding generates the JsonPath text of every
 * {@code AR_CONSENT_*}, {@code AR_TOPMED_*}, {@code AR_PHENO_*}, {@code AR_ALLOW_*} and {@code GATE_*} rule a new privilege gets. Those
 * generated strings are evaluated later against the introspection node, which now carries a BARE v3 Query at {@code $.query} instead of a
 * {query, resourceUUID} envelope at {@code $.query.query}. A generator left on the old shape does not fail loudly: PathNotFoundException is
 * a silent DENY for ALL_CONTAINS/ALL_EQUALS/IS_NOT_EMPTY rules and a silent GRANT for IS_EMPTY/ALL_CONTAINS_OR_EMPTY ones, so a stale
 * generator quietly widens or narrows access for every study onboarded after the wire changed.
 *
 * <p>This test mints the rules through the real generators, feeds them the exact {@code Map} the evaluator is handed
 * ({@code AuthorizationService#toRuleEvaluationNode}: {@code ObjectMapper.convertValue(TargetedRequest, Map)}), and runs them through the
 * real {@link AccessRuleService#evaluateAccessRule} -- i.e. the same com.jayway.jsonpath 2.9.0 production resolves. Treat a failure here as
 * an authorization regression in what NEW privileges will be granted, never as a test to be updated.
 *
 * <p>Deployed rows are untouched by any of this: {@code getOrCreateAccessRule} returns an existing rule by NAME, so an environment that
 * already holds these rule names keeps its stored text. Only a database that lacks them gets the shapes asserted below.
 */
class AccessRuleGeneratorWireTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** The exact conversion {@code AuthorizationService#toRuleEvaluationNode} performs before handing the node to JsonPath. */
    private static final TypeReference<Map<String, Object>> RULE_NODE_TYPE = new TypeReference<>() {};

    private static final String QUERY_TARGET = "/hpds/auth/v3/query";

    private static final String STUDY = "phs000001";
    private static final String CONSENT_GROUP = "c1";
    private static final String PROJECT_ALIAS = "TESTPROJ";

    /** Single backslashes: the form concept paths take on the wire. */
    private static final String STUDY_CONCEPT_PATH = "\\phs000001\\";
    private static final String OTHER_STUDY_CONCEPT_PATH = "\\phs000999\\pht99\\forbidden\\";
    private static final String HARMONIZED_CONCEPT_PATH = "\\DCC Harmonized data set\\";

    private static final String PARENT_CONSENT_BUCKET = "\\_consents\\";
    private static final String HARMONIZED_CONSENT_BUCKET = "\\_harmonized_consent\\";
    private static final String TOPMED_CONSENT_BUCKET = "\\_topmed_consents\\";

    private AccessRuleService accessRuleService;

    /**
     * Wired by hand with the shipped application.properties values, so the generated rule text is the text a real deployment would mint.
     * The repository never finds an existing rule, which is exactly the "fresh database" case this test is about.
     */
    @BeforeEach
    void setUp() {
        AccessRuleRepository repository = mock(AccessRuleRepository.class);
        when(repository.findByName(anyString())).thenReturn(null);
        when(repository.save(any(AccessRule.class))).thenAnswer(invocation -> {
            AccessRule saved = invocation.getArgument(0);
            saved.setUuid(UUID.randomUUID());
            return saved;
        });

        accessRuleService = new AccessRuleService(
            repository, HARMONIZED_CONSENT_BUCKET, PARENT_CONSENT_BUCKET, TOPMED_CONSENT_BUCKET, "", "COUNT,DATAFRAME",
            HARMONIZED_CONCEPT_PATH
        );
        accessRuleService.init();
    }

    // ---------------------------------------------------------------------------------------------------------------
    // The blanket guard: no generator anywhere may mint an envelope-era path.
    // ---------------------------------------------------------------------------------------------------------------

    /**
     * Every rule any generator mints, walked transitively through gates and sub-rules. {@code $.query.query.<field>} addressed a v1 Query
     * nested inside a {query, resourceUUID} envelope; nothing on this wire is shaped like that any more, so a rule still carrying it is
     * dead text that decides by accident.
     */
    @Test
    void noGeneratorMintsAnEnvelopeEraPath() {
        for (AccessRule rule : allGeneratedRules()) {
            String text = rule.getRule();
            if (text == null || text.isEmpty()) {
                continue;
            }
            assertFalse(
                text.contains("$.query.query"), "generator still mints the retired envelope path for " + rule.getName() + ": " + text
            );
            assertTrue(text.startsWith("$.query."), "generated rule " + rule.getName() + " does not bind the request node: " + text);
            assertNotNull(JsonPath.compile(text), "generated rule " + rule.getName() + " is not a compilable JsonPath: " + text);
        }
    }

    /** None of the v1-only Query members survives into a generated rule; each either moved or has no v3 counterpart at all. */
    @Test
    void noGeneratorMintsARetiredQueryField() {
        List<String> retired =
            List.of("categoryFilters", "numericFilters", "variantInfoFilters", "requiredFields", "anyRecordOf", "resourceUUID", "fields");

        for (AccessRule rule : allGeneratedRules()) {
            String text = rule.getRule();
            if (text == null) {
                continue;
            }
            for (String field : retired) {
                assertFalse(text.contains(field), "generated rule " + rule.getName() + " still reads v1 field " + field + ": " + text);
            }
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // AccessRuleService#loadAllowedQueryTypeRules -- was $.query.query.expectedResultType
    // ---------------------------------------------------------------------------------------------------------------

    @Test
    void allowedQueryTypeRulesReadTheResultTypeOffTheBareQuery() {
        AccessRule allowCount = byName(accessRuleService.getAllowedQueryTypeRules(), "AR_ALLOW_COUNT");

        assertTrue(grants(allowCount, inStudyQuery()));
        assertFalse(grants(allowCount, dataframeQuery()), "AR_ALLOW_COUNT must not pass a DATAFRAME query");
    }

    // ---------------------------------------------------------------------------------------------------------------
    // AccessRuleService#getGates / #configureHarmonizedAccessRule / #populateHarmonizedAccessRule
    // -- was $.query.query.categoryFilters.<consent concept path>[*]
    // ---------------------------------------------------------------------------------------------------------------

    @Test
    void consentGatesReadTheAuthorizationFilterBucketTheyNameAndOnlyThatOne() {
        AccessRule parentRule = accessRuleService.createConsentAccessRule(STUDY, CONSENT_GROUP, "PARENT", PARENT_CONSENT_BUCKET);
        parentRule.setSubAccessRule(new HashSet<>());
        accessRuleService.configureAccessRule(parentRule, STUDY, CONSENT_GROUP, STUDY_CONCEPT_PATH, PROJECT_ALIAS);

        AccessRule parentPresent = byName(parentRule.getGates(), "GATE_PARENT_CONSENT_PRESENT");
        AccessRule harmonizedMissing = byName(parentRule.getGates(), "GATE_HARMONIZED_CONSENT_MISSING");

        assertTrue(grants(parentPresent, inStudyQuery()), "the parent consent bucket is populated on this query");
        assertTrue(grants(harmonizedMissing, inStudyQuery()), "the harmonized consent bucket is absent from this query");

        Query harmonizedOnly = inStudyQuery()
            .setAuthorizationFilters(List.of(new AuthorizationFilter(HARMONIZED_CONSENT_BUCKET, Set.of(STUDY + "." + CONSENT_GROUP))));
        assertFalse(grants(parentPresent, harmonizedOnly), "a gate must read only the bucket it names");
        assertFalse(grants(harmonizedMissing, harmonizedOnly));
    }

    @Test
    void theHarmonizedGateSurvivesBothPlacesItIsMinted() {
        AccessRule configured = accessRuleService.createConsentAccessRule(STUDY, CONSENT_GROUP, "HARMONIZED", HARMONIZED_CONSENT_BUCKET);
        configured.setSubAccessRule(new HashSet<>());
        accessRuleService.configureHarmonizedAccessRule(configured, STUDY, STUDY_CONCEPT_PATH, PROJECT_ALIAS);

        AccessRule populated = accessRuleService.upsertHarmonizedAccessRule(STUDY, CONSENT_GROUP);
        populated.setSubAccessRule(new HashSet<>());
        accessRuleService.populateHarmonizedAccessRule(populated, STUDY_CONCEPT_PATH, STUDY, PROJECT_ALIAS);

        Query harmonized = inStudyQuery()
            .setAuthorizationFilters(List.of(new AuthorizationFilter(HARMONIZED_CONSENT_BUCKET, Set.of(STUDY + "." + CONSENT_GROUP))));

        assertTrue(grants(byName(configured.getGates(), "GATE_HARMONIZED_CONSENT_PRESENT"), harmonized));
        assertTrue(grants(byName(populated.getGates(), "GATE_HARMONIZED_CONSENT_PRESENT"), harmonized));
        assertFalse(grants(byName(configured.getGates(), "GATE_HARMONIZED_CONSENT_PRESENT"), inStudyQuery()));
    }

    // ---------------------------------------------------------------------------------------------------------------
    // AccessRuleService#createConsentAccessRule / #upsertTopmedAccessRule / #upsertHarmonizedAccessRule
    // -- was $.query.query.categoryFilters.<consent concept path>[*]
    // ---------------------------------------------------------------------------------------------------------------

    @Test
    void consentAccessRulesMatchTheStudyConsentValueInTheirOwnBucket() {
        AccessRule parent = bare(accessRuleService.createConsentAccessRule(STUDY, CONSENT_GROUP, "PARENT", PARENT_CONSENT_BUCKET));
        AccessRule topmed = bare(accessRuleService.upsertTopmedAccessRule(STUDY, CONSENT_GROUP, "TOPMED"));
        AccessRule harmonized = bare(accessRuleService.upsertHarmonizedAccessRule(STUDY, CONSENT_GROUP));

        String consent = STUDY + "." + CONSENT_GROUP;
        assertTrue(grants(parent, withConsents(new AuthorizationFilter(PARENT_CONSENT_BUCKET, Set.of(consent)))));
        assertTrue(grants(topmed, withConsents(new AuthorizationFilter(TOPMED_CONSENT_BUCKET, Set.of(consent)))));
        assertTrue(grants(harmonized, withConsents(new AuthorizationFilter(HARMONIZED_CONSENT_BUCKET, Set.of(consent)))));

        assertFalse(
            grants(parent, withConsents(new AuthorizationFilter(PARENT_CONSENT_BUCKET, Set.of("phs000999.c2")))),
            "another study's consent must not satisfy this study's rule"
        );
        assertFalse(
            grants(topmed, withConsents(new AuthorizationFilter(PARENT_CONSENT_BUCKET, Set.of(consent)))),
            "the topmed rule must not read the parent bucket"
        );
    }

    // ---------------------------------------------------------------------------------------------------------------
    // AccessRuleService#getTopmedRestrictedSubRules -- was $.query.query.variantInfoFilters[*].*VariantInfoFilters.*
    // ---------------------------------------------------------------------------------------------------------------

    @Test
    void topmedRestrictedSubRulesDenyGenomicFiltersOfEitherShape() {
        AccessRule rule = bare(accessRuleService.createConsentAccessRule(STUDY, CONSENT_GROUP, "PARENT", PARENT_CONSENT_BUCKET));
        rule.setSubAccessRule(new HashSet<>());
        accessRuleService.configureAccessRule(rule, STUDY, CONSENT_GROUP, STUDY_CONCEPT_PATH, PROJECT_ALIAS);

        AccessRule categorical = byName(rule.getSubAccessRule(), "AR_TOPMED_RESTRICTED_CATEGORICAL");
        AccessRule numeric = byName(rule.getSubAccessRule(), "AR_TOPMED_RESTRICTED_NUMERIC");

        assertTrue(grants(categorical, inStudyQuery()), "a query with no genomic filters carries nothing to restrict");
        assertTrue(grants(numeric, inStudyQuery()));

        Query categoricalGenomic = withGenomicFilters(new GenomicFilter("Gene_with_variant", List.of("APOE"), null, null));
        Query numericGenomic = withGenomicFilters(new GenomicFilter("Variant_frequency_as_text", null, 0.1f, 0.9f));

        // Both rules now bind the whole genomicFilters list: v3 gives no shape-independent way to tell a categorical genomic filter from a
        // numeric one, and their shared intent -- deny genomic filters outright -- does not need the distinction.
        assertFalse(grants(categorical, categoricalGenomic), "a categorical genomic filter must be denied");
        assertFalse(grants(numeric, categoricalGenomic));
        assertFalse(grants(categorical, numericGenomic));
        assertFalse(grants(numeric, numericGenomic), "a numeric genomic filter must be denied");
    }

    // ---------------------------------------------------------------------------------------------------------------
    // AccessRuleService#getPhenotypeSubRules -- was fields / categoryFilters / numericFilters / requiredFields /
    // anyRecordOf / anyRecordOfMulti
    // ---------------------------------------------------------------------------------------------------------------

    @Test
    void phenotypeSubRulesScopeEveryConceptPathTheBareQueryCarries() {
        AccessRule parent = subRuleHolder(accessRuleService.getPhenotypeSubRules(STUDY, STUDY_CONCEPT_PATH, PROJECT_ALIAS));

        assertTrue(grants(parent, inStudyQuery()), "every concept path in this query is under the granted study");

        assertFalse(grants(parent, selecting(OTHER_STUDY_CONCEPT_PATH)), "a select outside the study must be denied");
        assertFalse(
            grants(parent, filtering(PhenotypicFilterType.FILTER, OTHER_STUDY_CONCEPT_PATH, Set.of("x"), null, null)),
            "a categorical filter outside the study must be denied"
        );
        assertFalse(
            grants(parent, filtering(PhenotypicFilterType.FILTER, OTHER_STUDY_CONCEPT_PATH, null, 1.0, 2.0)),
            "a numeric filter outside the study must be denied"
        );
        assertFalse(
            grants(parent, filtering(PhenotypicFilterType.REQUIRED, OTHER_STUDY_CONCEPT_PATH, null, null, null)),
            "a required filter outside the study must be denied"
        );
        assertFalse(
            grants(parent, filtering(PhenotypicFilterType.ANY_RECORD_OF, OTHER_STUDY_CONCEPT_PATH, null, null, null)),
            "an any-record-of filter outside the study must be denied"
        );
    }

    /**
     * The underscore concept paths (accession fields, consent listings) stay allowed for every study -- they are merged into the same rules
     * as the study concept path, so the OR of merged values has to keep letting them through.
     */
    @Test
    void phenotypeSubRulesStillAllowTheUnderscoreConceptPaths() {
        AccessRule parent = subRuleHolder(accessRuleService.getPhenotypeSubRules(STUDY, STUDY_CONCEPT_PATH, PROJECT_ALIAS));

        assertTrue(grants(parent, selecting("\\_Parent Study Accession with Subject ID\\")));
        assertTrue(grants(parent, filtering(PhenotypicFilterType.FILTER, PARENT_CONSENT_BUCKET, Set.of("phs000001.c1"), null, null)));
    }

    // ---------------------------------------------------------------------------------------------------------------
    // AccessRuleService#getHarmonizedSubRules
    // ---------------------------------------------------------------------------------------------------------------

    @Test
    void harmonizedSubRulesAllowTheHarmonizedConceptPathAndDenyOtherStudies() {
        AccessRule parent = subRuleHolder(harmonizedSubRules());

        assertTrue(grants(parent, selecting(HARMONIZED_CONCEPT_PATH + "age\\")));
        assertTrue(grants(parent, filtering(PhenotypicFilterType.FILTER, HARMONIZED_CONCEPT_PATH + "sex\\", Set.of("male"), null, null)));
        assertFalse(grants(parent, selecting(OTHER_STUDY_CONCEPT_PATH)));
    }

    // ---------------------------------------------------------------------------------------------------------------
    // AccessRuleService#getPhenotypeRestrictedSubRules -- genomic-only privileges
    // ---------------------------------------------------------------------------------------------------------------

    @Test
    void phenotypeRestrictedSubRulesConfineAQueryToGenomicsAndTheUnderscorePaths() {
        AccessRule parent = subRuleHolder(accessRuleService.getPhenotypeRestrictedSubRules(STUDY, CONSENT_GROUP, PROJECT_ALIAS));

        assertTrue(grants(parent, genomicOnlyQuery()), "a purely genomic query is what this privilege exists to allow");
        assertTrue(
            grants(parent, filtering(PhenotypicFilterType.FILTER, TOPMED_CONSENT_BUCKET, Set.of(STUDY + "." + CONSENT_GROUP), null, null)),
            "filtering on the topmed consent path itself stays allowed"
        );
        assertFalse(
            grants(parent, filtering(PhenotypicFilterType.FILTER, STUDY_CONCEPT_PATH + "bmi\\", null, 18.0, 30.0)),
            "a phenotypic filter on real study data must be denied on a genomic-only privilege"
        );
        assertFalse(
            grants(parent, filtering(PhenotypicFilterType.REQUIRED, TOPMED_CONSENT_BUCKET, null, null, null)),
            "a required filter is denied outright, even on an otherwise allowed concept path"
        );
        assertFalse(grants(parent, selecting(OTHER_STUDY_CONCEPT_PATH)), "selecting another study's data must be denied");
    }

    // ---------------------------------------------------------------------------------------------------------------
    // The sub-rule PrivilegeService attaches to its topmed+parent rule (was an inline $.query.query.categoryFilters string there)
    // ---------------------------------------------------------------------------------------------------------------

    @Test
    void theTopmedConsentAllowanceSubRuleBindsTheBareQuery() {
        AccessRule allowance = accessRuleService.createTopmedConsentAllowanceSubRule();

        assertTrue(allowance.getRule().startsWith("$.query.phenotypicClause"), allowance.getRule());
        assertTrue(grants(allowance, filtering(PhenotypicFilterType.FILTER, TOPMED_CONSENT_BUCKET, Set.of("phs000001.c1"), null, null)));
        assertTrue(grants(allowance, genomicOnlyQuery()), "a query with no phenotypic filter at all is not this rule's business");
        assertFalse(
            grants(allowance, filtering(PhenotypicFilterType.FILTER, OTHER_STUDY_CONCEPT_PATH, Set.of("x"), null, null)),
            "on its own -- before it is merged with the study's own allowances -- this rule allows only the topmed consent path"
        );
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Fixtures
    // ---------------------------------------------------------------------------------------------------------------

    /** A realistic in-study bare v3 Query: one of every phenotypic filter shape, a select, and a populated parent consent bucket. */
    private static Query inStudyQuery() {
        PhenotypicClause clause = new PhenotypicSubquery(
            false,
            List.of(
                new PhenotypicFilter(PhenotypicFilterType.FILTER, STUDY_CONCEPT_PATH + "sex\\", Set.of("male"), null, null, null),
                new PhenotypicSubquery(
                    false,
                    List.of(
                        new PhenotypicFilter(PhenotypicFilterType.FILTER, STUDY_CONCEPT_PATH + "bmi\\", null, 18.0, 30.0, null),
                        new PhenotypicFilter(PhenotypicFilterType.REQUIRED, STUDY_CONCEPT_PATH + "race\\", null, null, null, null),
                        new PhenotypicFilter(PhenotypicFilterType.ANY_RECORD_OF, STUDY_CONCEPT_PATH + "labs\\", null, null, null, null)
                    ), Operator.OR
                )
            ), Operator.AND
        );

        return new Query(
            List.of(STUDY_CONCEPT_PATH + "age\\"),
            List.of(new AuthorizationFilter(PARENT_CONSENT_BUCKET, Set.of(STUDY + "." + CONSENT_GROUP))), clause, List.of(),
            ResultType.COUNT, null, null
        );
    }

    private static Query dataframeQuery() {
        Query base = inStudyQuery();
        return new Query(
            base.select(), base.authorizationFilters(), base.phenotypicClause(), base.genomicFilters(), ResultType.DATAFRAME, null, null
        );
    }

    private static Query withConsents(AuthorizationFilter... filters) {
        return inStudyQuery().setAuthorizationFilters(List.of(filters));
    }

    /** No phenotypic filters and no select: exactly what a genomic-only privilege is meant to permit. */
    private static Query genomicOnlyQuery() {
        return new Query(
            List.of(), List.of(new AuthorizationFilter(TOPMED_CONSENT_BUCKET, Set.of(STUDY + "." + CONSENT_GROUP))), null,
            List.of(new GenomicFilter("Gene_with_variant", List.of("APOE"), null, null)), ResultType.COUNT, null, null
        );
    }

    private static Query withGenomicFilters(GenomicFilter... filters) {
        Query base = inStudyQuery();
        return new Query(
            base.select(), base.authorizationFilters(), base.phenotypicClause(), List.of(filters), base.expectedResultType(), null, null
        );
    }

    /** A query whose ONLY concept path is the one under test, so a denial can only come from that path. */
    private static Query selecting(String conceptPath) {
        return new Query(
            List.of(conceptPath), List.of(new AuthorizationFilter(PARENT_CONSENT_BUCKET, Set.of(STUDY + "." + CONSENT_GROUP))), null,
            List.of(), ResultType.COUNT, null, null
        );
    }

    private static Query filtering(PhenotypicFilterType type, String conceptPath, Set<String> values, Double min, Double max) {
        return new Query(
            List.of(), List.of(new AuthorizationFilter(PARENT_CONSENT_BUCKET, Set.of(STUDY + "." + CONSENT_GROUP))),
            new PhenotypicFilter(type, conceptPath, values, min, max, null), List.of(), ResultType.COUNT, null, null
        );
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Plumbing
    // ---------------------------------------------------------------------------------------------------------------

    /**
     * Runs a generated rule through the real evaluator against the exact node the evaluator is handed in production: the plain
     * {@code Map}/{@code List} tree {@code ObjectMapper.convertValue} produces from a {@link TargetedRequest}, never a Jackson node.
     */
    private boolean grants(AccessRule rule, Query query) {
        TargetedRequest request = new TargetedRequest(QUERY_TARGET, MAPPER.valueToTree(query));
        return accessRuleService.evaluateAccessRule(MAPPER.convertValue(request, RULE_NODE_TYPE), rule);
    }

    /** A rule of its own, detached from the gates/sub-rules its caller would attach, so one assertion tests one JsonPath. */
    private static AccessRule bare(AccessRule rule) {
        rule.setGates(new HashSet<>());
        rule.setSubAccessRule(new HashSet<>());
        return rule;
    }

    /** Sub-rules are ANDed and merged by the evaluator; hang them off an empty parent so the merge path runs as it does in production. */
    private static AccessRule subRuleHolder(Collection<? extends AccessRule> subRules) {
        AccessRule holder = new AccessRule();
        holder.setUuid(UUID.randomUUID());
        holder.setName("AR_TEST_HOLDER");
        holder.setRule("");
        holder.setType(AccessRule.TypeNaming.ALL_CONTAINS);
        holder.setSubAccessRule(new HashSet<>(subRules));
        return holder;
    }

    private Collection<? extends AccessRule> harmonizedSubRules() {
        AccessRule harmonized = accessRuleService.upsertHarmonizedAccessRule(STUDY, CONSENT_GROUP);
        harmonized.setSubAccessRule(new HashSet<>());
        accessRuleService.populateHarmonizedAccessRule(harmonized, STUDY_CONCEPT_PATH, STUDY, PROJECT_ALIAS);
        return harmonized.getSubAccessRule();
    }

    /** Every rule every generator in this service mints, walked transitively through gates and sub-rules. */
    private List<AccessRule> allGeneratedRules() {
        List<AccessRule> roots = new ArrayList<>();

        AccessRule clinicalParent = accessRuleService.createConsentAccessRule(STUDY, CONSENT_GROUP, "PARENT", PARENT_CONSENT_BUCKET);
        clinicalParent.setSubAccessRule(new HashSet<>());
        accessRuleService.configureAccessRule(clinicalParent, STUDY, CONSENT_GROUP, STUDY_CONCEPT_PATH, PROJECT_ALIAS);
        roots.add(clinicalParent);

        AccessRule clinicalHarmonized =
            accessRuleService.createConsentAccessRule(STUDY, CONSENT_GROUP, "HARMONIZED", HARMONIZED_CONSENT_BUCKET);
        clinicalHarmonized.setSubAccessRule(new HashSet<>());
        accessRuleService.configureHarmonizedAccessRule(clinicalHarmonized, STUDY, STUDY_CONCEPT_PATH, PROJECT_ALIAS);
        roots.add(clinicalHarmonized);

        AccessRule topmedParent = accessRuleService.upsertTopmedAccessRule(STUDY, CONSENT_GROUP, "TOPMED+PARENT");
        topmedParent.setSubAccessRule(new HashSet<>());
        accessRuleService
            .configureClinicalAccessRuleWithPhenoSubRule(topmedParent, STUDY, CONSENT_GROUP, STUDY_CONCEPT_PATH, PROJECT_ALIAS);
        roots.add(topmedParent);

        AccessRule topmedOnly = accessRuleService.upsertTopmedAccessRule(STUDY, CONSENT_GROUP, "TOPMED");
        topmedOnly.setSubAccessRule(new HashSet<>());
        accessRuleService.populateTopmedAccessRule(topmedOnly, false);
        topmedOnly.getSubAccessRule().addAll(accessRuleService.getPhenotypeRestrictedSubRules(STUDY, CONSENT_GROUP, PROJECT_ALIAS));
        roots.add(topmedOnly);

        AccessRule harmonizedTopmed = accessRuleService.upsertHarmonizedAccessRule(STUDY, CONSENT_GROUP);
        harmonizedTopmed.setSubAccessRule(new HashSet<>());
        accessRuleService.populateHarmonizedAccessRule(harmonizedTopmed, STUDY_CONCEPT_PATH, STUDY, PROJECT_ALIAS);
        roots.add(harmonizedTopmed);

        roots.add(accessRuleService.createTopmedConsentAllowanceSubRule());

        List<AccessRule> flattened = new ArrayList<>();
        collect(roots, flattened, new HashSet<>());
        return flattened;
    }

    private static void collect(Collection<? extends AccessRule> rules, List<AccessRule> into, Set<String> seen) {
        for (AccessRule rule : rules) {
            if (rule == null || !seen.add(rule.getName())) {
                continue;
            }
            into.add(rule);
            if (rule.getGates() != null) {
                collect(rule.getGates(), into, seen);
            }
            if (rule.getSubAccessRule() != null) {
                collect(rule.getSubAccessRule(), into, seen);
            }
        }
    }

    private static AccessRule byName(Collection<? extends AccessRule> rules, String name) {
        return rules.stream().filter(rule -> name.equals(rule.getName())).findFirst().orElseThrow(
            () -> new AssertionError("no generated rule named " + name + " in " + rules.stream().map(AccessRule::getName).toList())
        );
    }
}
