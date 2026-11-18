package edu.harvard.hms.dbmi.avillach.hpds.data.query.v3;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import edu.harvard.hms.dbmi.avillach.hpds.data.query.ResultType;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.AuthorizationFilter;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.GenomicFilter;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.Operator;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.PhenotypicClause;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.PhenotypicFilter;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.PhenotypicFilterType;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.PhenotypicSubquery;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.Query;

class QueryIdentityTest {

    private static final UUID USER = UUID.fromString("5f0e1a3c-9b7d-4c2e-8a1f-3d6b2c4e7f90");
    private static final UUID OTHER_USER = UUID.fromString("0b9c8d7e-6f5a-4b3c-9d2e-1f0a9b8c7d6e");
    private static final String AGE = "\\demographics\\AGE\\";
    private static final String SEX = "\\demographics\\SEX\\";

    private static final String GOLDEN_CANONICAL = "{\"v\":1,\"select\":[\"\\\\demographics\\\\SEX\\\\\",\"\\\\demographics\\\\AGE\\\\\"],"
        + "\"phenotypicClause\":{\"clause\":\"subquery\",\"not\":false,\"phenotypicClauses\":[{\"clause\":\"filter\","
        + "\"phenotypicFilterType\":\"FILTER\",\"conceptPath\":\"\\\\demographics\\\\SEX\\\\\",\"values\":[\"Female\",\"Male\","
        + "\"caf\\u00e9 \\\"quoted\\\"\",\"tab\\u0009separated\"],\"min\":null,\"max\":null,\"not\":false},{\"clause\":\"filter\","
        + "\"phenotypicFilterType\":\"FILTER\",\"conceptPath\":\"\\\\demographics\\\\AGE\\\\\",\"values\":[],\"min\":0x4032000000000000,"
        + "\"max\":0x4055500000000000,\"not\":true},{\"clause\":\"filter\",\"phenotypicFilterType\":\"FILTER\","
        + "\"conceptPath\":\"\\\\demographics\\\\AGE\\\\\",\"values\":null,\"min\":0x8000000000000000,\"max\":0x0000000000000000,"
        + "\"not\":false},{\"clause\":\"subquery\",\"not\":true,\"phenotypicClauses\":[{\"clause\":\"filter\","
        + "\"phenotypicFilterType\":\"REQUIRED\",\"conceptPath\":\"\\\\labs\\\\A1C\\\\\",\"values\":null,\"min\":null,\"max\":null,"
        + "\"not\":false},{\"clause\":\"filter\",\"phenotypicFilterType\":\"ANY_RECORD_OF\",\"conceptPath\":null,\"values\":null,"
        + "\"min\":null,\"max\":null,\"not\":false}],\"operator\":\"OR\"},{\"clause\":\"subquery\",\"not\":false,\"phenotypicClauses\":[],"
        + "\"operator\":null}],\"operator\":\"AND\"},\"genomicFilters\":[{\"key\":\"Gene_with_variant\",\"values\":[\"BRCA2\",\"APOE\"],"
        + "\"min\":null,\"max\":null},{\"key\":\"Variant_frequency_in_gnomAD\",\"values\":null,\"min\":0x3fe0000000000000,"
        + "\"max\":0x4059000000000000},{\"key\":null,\"values\":[],\"min\":null,\"max\":null}],\"expectedResultType\":\"DATAFRAME_PFB\","
        + "\"sample\":true}";
    private static final String GOLDEN_ID = "8c6f2644-054c-51f2-9a4a-d129acf11b06";
    private static final String GOLDEN_GENERAL_ID = "434e9f0e-eb55-52b7-b0e7-c2af73357be8";

    private static Query query(List<String> select, PhenotypicClause clause, List<GenomicFilter> genomic, ResultType type, boolean sample) {
        return new Query(select, List.of(), clause, genomic, type, null, null, sample);
    }

    private static Query withClause(PhenotypicClause clause) {
        return query(List.of(AGE), clause, List.of(), ResultType.DATAFRAME, false);
    }

    private static Query withGenomic(GenomicFilter... filters) {
        return query(List.of(AGE), null, Arrays.asList(filters), ResultType.DATAFRAME, false);
    }

    private static PhenotypicFilter categorical(Set<String> values) {
        return new PhenotypicFilter(PhenotypicFilterType.FILTER, SEX, values, null, null, null);
    }

    private static PhenotypicFilter numeric(Double min, Double max) {
        return new PhenotypicFilter(PhenotypicFilterType.FILTER, AGE, null, min, max, null);
    }

    private static PhenotypicSubquery all(PhenotypicClause... clauses) {
        return new PhenotypicSubquery(null, Arrays.asList(clauses), Operator.AND);
    }

    private static Query fullyPopulated() {
        Set<String> sexes = new LinkedHashSet<>(List.of("Male", "café \"quoted\"", "tab\tseparated", "Female"));
        PhenotypicClause tree = new PhenotypicSubquery(
            false,
            List.of(
                new PhenotypicFilter(PhenotypicFilterType.FILTER, SEX, sexes, null, null, false),
                new PhenotypicFilter(PhenotypicFilterType.FILTER, AGE, Set.of(), 18.0, 85.25, true),
                new PhenotypicFilter(PhenotypicFilterType.FILTER, AGE, null, -0.0, 0.0, null),
                new PhenotypicSubquery(
                    true,
                    List.of(
                        new PhenotypicFilter(PhenotypicFilterType.REQUIRED, "\\labs\\A1C\\", null, null, null, null),
                        new PhenotypicFilter(PhenotypicFilterType.ANY_RECORD_OF, null, null, null, null, null)
                    ), Operator.OR
                ), new PhenotypicSubquery(null, null, null)
            ), Operator.AND
        );
        List<GenomicFilter> genomic = List.of(
            new GenomicFilter("Gene_with_variant", List.of("BRCA2", "APOE"), null, null),
            new GenomicFilter("Variant_frequency_in_gnomAD", null, 0.5f, 100f), new GenomicFilter(null, List.of(), null, null)
        );
        List<AuthorizationFilter> consents = List.of(new AuthorizationFilter("\\_consents\\", Set.of("phs000007.c1", "phs000007.c2")));
        return new Query(
            List.of(SEX, AGE), consents, tree, genomic, ResultType.DATAFRAME_PFB, UUID.fromString("8694e3d4-5cb4-410f-8431-993445e6d3f6"),
            UUID.fromString("11111111-2222-4333-8444-555555555555"), true
        );
    }

    private static String writtenName(String canonical, String member) {
        String opening = "\"" + member + "\":\"";
        int start = canonical.indexOf(opening) + opening.length();
        return canonical.substring(start, canonical.indexOf('"', start));
    }

    private static Query fromJson(String json) {
        try {
            return new ObjectMapper().readValue(json, Query.class);
        } catch (JsonProcessingException unreadable) {
            throw new IllegalArgumentException(unreadable);
        }
    }

    private static byte[] digest(String algorithm, byte[]... parts) {
        try {
            MessageDigest digest = MessageDigest.getInstance(algorithm);
            for (byte[] part : parts) {
                digest.update(part);
            }
            return digest.digest();
        } catch (NoSuchAlgorithmException missing) {
            throw new IllegalStateException(missing);
        }
    }

    private static UUID rfc4122Version5(UUID namespace, byte[] name) {
        byte[] namespaceBytes =
            ByteBuffer.allocate(16).putLong(namespace.getMostSignificantBits()).putLong(namespace.getLeastSignificantBits()).array();
        ByteBuffer hash = ByteBuffer.wrap(digest("SHA-1", namespaceBytes, name));
        long high = hash.getLong() & 0xffffffffffff0fffL | 0x0000000000005000L;
        long low = hash.getLong() & 0x3fffffffffffffffL | 0x8000000000000000L;
        return new UUID(high, low);
    }

    @Test
    void sameUserAndQueryGiveTheSameId() {
        assertThat(QueryIdentity.idFor(USER, fullyPopulated())).isEqualTo(QueryIdentity.idFor(USER, fullyPopulated()));
    }

    @Test
    void differentUsersGiveDifferentIds() {
        assertThat(QueryIdentity.idFor(USER, fullyPopulated())).isNotEqualTo(QueryIdentity.idFor(OTHER_USER, fullyPopulated()));
    }

    @Test
    void sampleChangesTheId() {
        Query full = query(List.of(AGE), null, List.of(), ResultType.DATAFRAME, false);
        Query sample = query(List.of(AGE), null, List.of(), ResultType.DATAFRAME, true);

        assertThat(QueryIdentity.idFor(USER, sample)).isNotEqualTo(QueryIdentity.idFor(USER, full));
        assertThat(CanonicalQuery.of(full)).contains("\"sample\":false");
        assertThat(CanonicalQuery.of(sample)).contains("\"sample\":true");
    }

    @Test
    void resultTypeChangesTheId() {
        Query dataframe = query(List.of(AGE), null, List.of(), ResultType.DATAFRAME, false);
        Query pfb = query(List.of(AGE), null, List.of(), ResultType.DATAFRAME_PFB, false);
        Query untyped = query(List.of(AGE), null, List.of(), null, false);

        assertThat(QueryIdentity.idFor(USER, pfb)).isNotEqualTo(QueryIdentity.idFor(USER, dataframe));
        assertThat(QueryIdentity.idFor(USER, untyped)).isNotEqualTo(QueryIdentity.idFor(USER, dataframe));
        assertThat(CanonicalQuery.of(untyped)).contains("\"expectedResultType\":null");
    }

    @Test
    void authorizationFiltersIdAndPicsureIdDoNotChangeTheId() {
        Query scoped = fullyPopulated();
        Query bare = new Query(
            scoped.select(), null, scoped.phenotypicClause(), scoped.genomicFilters(), scoped.expectedResultType(), null, null,
            scoped.sample()
        );
        Query otherConsents = scoped.setAuthorizationFilters(List.of(new AuthorizationFilter("\\_consents\\", Set.of("phs000001.c9"))));

        assertThat(CanonicalQuery.of(scoped)).isEqualTo(CanonicalQuery.of(bare)).isEqualTo(CanonicalQuery.of(otherConsents));
        assertThat(QueryIdentity.idFor(USER, scoped)).isEqualTo(QueryIdentity.idFor(USER, bare))
            .isEqualTo(QueryIdentity.idFor(USER, otherConsents));
        assertThat(CanonicalQuery.of(scoped)).doesNotContain("phs000007", "8694e3d4", "11111111", "authorizationFilters");
    }

    @Test
    void setOrderDoesNotChangeTheId() {
        Set<String> insertionOrder = new LinkedHashSet<>(List.of("Male", "Female", "Other"));
        Set<String> reverseInsertionOrder = new LinkedHashSet<>(List.of("Other", "Female", "Male"));
        Set<String> hashed = new HashSet<>(List.of("Female", "Other", "Male"));
        Set<String> descending = new TreeSet<>(Comparator.reverseOrder());
        descending.addAll(hashed);

        UUID expected = QueryIdentity.idFor(USER, withClause(categorical(insertionOrder)));

        assertThat(QueryIdentity.idFor(USER, withClause(categorical(reverseInsertionOrder)))).isEqualTo(expected);
        assertThat(QueryIdentity.idFor(USER, withClause(categorical(hashed)))).isEqualTo(expected);
        assertThat(QueryIdentity.idFor(USER, withClause(categorical(descending)))).isEqualTo(expected);
        assertThat(QueryIdentity.idFor(USER, withClause(categorical(Set.of("Male", "Female", "Other"))))).isEqualTo(expected);
        assertThat(CanonicalQuery.of(withClause(categorical(descending)))).contains("\"values\":[\"Female\",\"Male\",\"Other\"]");
    }

    @Test
    void setValuesInsideANestedSubqueryAreSorted() {
        Query oneWay = withClause(all(all(categorical(new LinkedHashSet<>(List.of("b", "a"))))));
        Query otherWay = withClause(all(all(categorical(new LinkedHashSet<>(List.of("a", "b"))))));

        assertThat(QueryIdentity.idFor(USER, oneWay)).isEqualTo(QueryIdentity.idFor(USER, otherWay));
    }

    @Test
    void selectOrderChangesTheId() {
        Query ageFirst = query(List.of(AGE, SEX), null, List.of(), ResultType.DATAFRAME, false);
        Query sexFirst = query(List.of(SEX, AGE), null, List.of(), ResultType.DATAFRAME, false);

        assertThat(QueryIdentity.idFor(USER, ageFirst)).isNotEqualTo(QueryIdentity.idFor(USER, sexFirst));
    }

    @Test
    void clauseOrderChangesTheId() {
        PhenotypicFilter sex = categorical(Set.of("Male"));
        PhenotypicFilter age = numeric(18.0, null);

        assertThat(QueryIdentity.idFor(USER, withClause(all(sex, age)))).isNotEqualTo(QueryIdentity.idFor(USER, withClause(all(age, sex))));
    }

    @Test
    void genomicFilterAndGenomicValueOrderChangeTheId() {
        GenomicFilter gene = new GenomicFilter("Gene_with_variant", List.of("APOE", "BRCA2"), null, null);
        GenomicFilter geneReversed = new GenomicFilter("Gene_with_variant", List.of("BRCA2", "APOE"), null, null);
        GenomicFilter frequency = new GenomicFilter("Variant_frequency_in_gnomAD", null, 0.5f, null);

        assertThat(QueryIdentity.idFor(USER, withGenomic(gene, frequency)))
            .isNotEqualTo(QueryIdentity.idFor(USER, withGenomic(frequency, gene)));
        assertThat(QueryIdentity.idFor(USER, withGenomic(gene))).isNotEqualTo(QueryIdentity.idFor(USER, withGenomic(geneReversed)));
    }

    @Test
    void nullAndEmptySelectGenomicFiltersAndSubqueryClausesGiveTheSameId() {
        Query nulls =
            new Query(null, null, all(new PhenotypicSubquery(null, null, Operator.OR)), null, ResultType.DATAFRAME, null, null, false);
        Query empties = new Query(
            new ArrayList<>(), new ArrayList<>(), all(new PhenotypicSubquery(null, new ArrayList<>(), Operator.OR)), new ArrayList<>(),
            ResultType.DATAFRAME, null, null, false
        );

        assertThat(CanonicalQuery.of(nulls)).isEqualTo(CanonicalQuery.of(empties));
        assertThat(QueryIdentity.idFor(USER, nulls)).isEqualTo(QueryIdentity.idFor(USER, empties));
    }

    @Test
    void nullAndEmptyPhenotypicValuesGiveDifferentIds() {
        Query rangeOnly = withClause(new PhenotypicFilter(PhenotypicFilterType.FILTER, AGE, null, 18.0, null, null));
        Query rangeAndNoValue = withClause(new PhenotypicFilter(PhenotypicFilterType.FILTER, AGE, new HashSet<>(), 18.0, null, null));

        assertThat(CanonicalQuery.of(rangeOnly)).contains("\"values\":null,\"min\":0x4032000000000000");
        assertThat(CanonicalQuery.of(rangeAndNoValue)).contains("\"values\":[],\"min\":0x4032000000000000");
        assertThat(QueryIdentity.idFor(USER, rangeOnly)).isNotEqualTo(QueryIdentity.idFor(USER, rangeAndNoValue));
    }

    @Test
    void nullAndEmptyPhenotypicValuesInJsonGiveDifferentIds() {
        String filter = "{\"phenotypicClause\":{\"phenotypicFilterType\":\"FILTER\",\"conceptPath\":\"a\",%s\"min\":18},"
            + "\"expectedResultType\":\"COUNT\"}";
        Query omitted = fromJson(filter.formatted(""));
        Query explicitNull = fromJson(filter.formatted("\"values\":null,"));
        Query explicitEmpty = fromJson(filter.formatted("\"values\":[],"));

        assertThat(QueryIdentity.idFor(USER, explicitNull)).isEqualTo(QueryIdentity.idFor(USER, omitted));
        assertThat(QueryIdentity.idFor(USER, explicitEmpty)).isNotEqualTo(QueryIdentity.idFor(USER, omitted));
    }

    @Test
    void nullAndEmptyGenomicValuesGiveDifferentIds() {
        Query noValues = withGenomic(new GenomicFilter("Gene_with_variant", null, null, null));
        Query emptyValues = withGenomic(new GenomicFilter("Gene_with_variant", List.of(), null, null));

        assertThat(CanonicalQuery.of(noValues)).contains("\"key\":\"Gene_with_variant\",\"values\":null,");
        assertThat(CanonicalQuery.of(emptyValues)).contains("\"key\":\"Gene_with_variant\",\"values\":[],");
        assertThat(QueryIdentity.idFor(USER, noValues)).isNotEqualTo(QueryIdentity.idFor(USER, emptyValues));
    }

    @Test
    void theVersionMarkerIsTheFirstMember() {
        Query smallest = new Query(null, null, null, null, null, null, null, false);

        assertThat(CanonicalQuery.FORM_VERSION).isEqualTo(1);
        assertThat(CanonicalQuery.of(smallest)).startsWith("{\"v\":1,\"select\":");
        assertThat(CanonicalQuery.of(fullyPopulated())).startsWith("{\"v\":" + CanonicalQuery.FORM_VERSION + ",");
    }

    @Test
    void nullEmptyAndOmittedCollectionsInJsonGiveTheSameId() {
        Query explicitNull = fromJson("{\"select\":null,\"genomicFilters\":null,\"expectedResultType\":\"COUNT\"}");
        Query explicitEmpty = fromJson("{\"select\":[],\"genomicFilters\":[],\"expectedResultType\":\"COUNT\",\"sample\":false}");
        Query omitted = fromJson("{\"expectedResultType\":\"COUNT\"}");

        UUID expected = QueryIdentity.idFor(USER, omitted);

        assertThat(QueryIdentity.idFor(USER, explicitNull)).isEqualTo(expected);
        assertThat(QueryIdentity.idFor(USER, explicitEmpty)).isEqualTo(expected);
    }

    @Test
    void anAbsentClauseAndAnEmptySubqueryAreDifferentQueries() {
        assertThat(QueryIdentity.idFor(USER, withClause(null))).isNotEqualTo(QueryIdentity.idFor(USER, withClause(all())));
    }

    @Test
    void nullNotAndFalseNotGiveTheSameIdAndTrueDiffers() {
        PhenotypicFilter unset = new PhenotypicFilter(PhenotypicFilterType.REQUIRED, AGE, null, null, null, null);
        PhenotypicFilter notNegated = new PhenotypicFilter(PhenotypicFilterType.REQUIRED, AGE, null, null, null, false);
        PhenotypicFilter negated = new PhenotypicFilter(PhenotypicFilterType.REQUIRED, AGE, null, null, null, true);
        Query unsetSubquery = withClause(new PhenotypicSubquery(null, List.of(unset), Operator.AND));
        Query plainSubquery = withClause(new PhenotypicSubquery(false, List.of(unset), Operator.AND));
        Query negatedSubquery = withClause(new PhenotypicSubquery(true, List.of(unset), Operator.AND));

        assertThat(QueryIdentity.idFor(USER, withClause(unset))).isEqualTo(QueryIdentity.idFor(USER, withClause(notNegated)));
        assertThat(QueryIdentity.idFor(USER, withClause(negated))).isNotEqualTo(QueryIdentity.idFor(USER, withClause(unset)));
        assertThat(QueryIdentity.idFor(USER, unsetSubquery)).isEqualTo(QueryIdentity.idFor(USER, plainSubquery));
        assertThat(QueryIdentity.idFor(USER, negatedSubquery)).isNotEqualTo(QueryIdentity.idFor(USER, unsetSubquery));
    }

    @Test
    void operatorAndFilterTypeChangeTheId() {
        PhenotypicFilter required = new PhenotypicFilter(PhenotypicFilterType.REQUIRED, AGE, null, null, null, null);
        PhenotypicFilter anyRecord = new PhenotypicFilter(PhenotypicFilterType.ANY_RECORD_OF, AGE, null, null, null, null);
        Query and = withClause(new PhenotypicSubquery(null, List.of(required), Operator.AND));
        Query or = withClause(new PhenotypicSubquery(null, List.of(required), Operator.OR));

        assertThat(QueryIdentity.idFor(USER, and)).isNotEqualTo(QueryIdentity.idFor(USER, or));
        assertThat(QueryIdentity.idFor(USER, withClause(required))).isNotEqualTo(QueryIdentity.idFor(USER, withClause(anyRecord)));
    }

    @Test
    void equalNumbersHaveOneRepresentation() {
        String integerJson = "{\"phenotypicClause\":{\"phenotypicFilterType\":\"FILTER\",\"conceptPath\":\"a\",\"min\":1,\"max\":10},"
            + "\"genomicFilters\":[{\"key\":\"k\",\"min\":1,\"max\":2}],\"expectedResultType\":\"COUNT\"}";
        String decimalJson = "{\"phenotypicClause\":{\"phenotypicFilterType\":\"FILTER\",\"conceptPath\":\"a\",\"min\":1.0,\"max\":1.0E1},"
            + "\"genomicFilters\":[{\"key\":\"k\",\"min\":1.00,\"max\":0.2e1}],\"expectedResultType\":\"COUNT\"}";

        assertThat(CanonicalQuery.of(fromJson(integerJson))).isEqualTo(CanonicalQuery.of(fromJson(decimalJson)));
        assertThat(CanonicalQuery.of(withClause(numeric(1.0, null)))).contains("\"min\":0x3ff0000000000000,\"max\":null");
    }

    @Test
    void negativeZeroAndZeroGiveDifferentIds() {
        Query zero = withClause(numeric(0.0, null));
        Query negativeZero = withClause(numeric(-0.0, null));

        assertThat(CanonicalQuery.of(zero)).contains("\"min\":0x0000000000000000,");
        assertThat(CanonicalQuery.of(negativeZero)).contains("\"min\":0x8000000000000000,");
        assertThat(QueryIdentity.idFor(USER, zero)).isNotEqualTo(QueryIdentity.idFor(USER, negativeZero));
        assertThat(QueryIdentity.idFor(USER, withGenomic(new GenomicFilter("k", null, 0f, null))))
            .isNotEqualTo(QueryIdentity.idFor(USER, withGenomic(new GenomicFilter("k", null, -0f, null))));
    }

    @Test
    void everyNanHasOneRepresentation() {
        double otherNan = Double.longBitsToDouble(0x7ff0000000000001L);

        assertThat(Double.doubleToRawLongBits(otherNan)).isNotEqualTo(Double.doubleToRawLongBits(Double.NaN));
        assertThat(CanonicalQuery.of(withClause(numeric(otherNan, null))))
            .isEqualTo(CanonicalQuery.of(withClause(numeric(Double.NaN, null)))).contains("\"min\":0x7ff8000000000000,");
    }

    @Test
    void differentNumbersAndBoundsChangeTheId() {
        UUID minOne = QueryIdentity.idFor(USER, withClause(numeric(1.0, null)));

        assertThat(QueryIdentity.idFor(USER, withClause(numeric(null, 1.0)))).isNotEqualTo(minOne);
        assertThat(QueryIdentity.idFor(USER, withClause(numeric(1.0000000000000002, null)))).isNotEqualTo(minOne);
        assertThat(QueryIdentity.idFor(USER, withGenomic(new GenomicFilter("k", null, 1f, null))))
            .isNotEqualTo(QueryIdentity.idFor(USER, withGenomic(new GenomicFilter("k", null, null, 1f))));
    }

    @Test
    void stringsCannotRunTogetherOrEscapeTheirQuotes() {
        Query twoPaths = query(List.of("a", "b"), null, List.of(), ResultType.DATAFRAME, false);
        Query onePathWithDelimiters = query(List.of("a\",\"b"), null, List.of(), ResultType.DATAFRAME, false);
        Query literalEscape = query(List.of("\\u00e9"), null, List.of(), ResultType.DATAFRAME, false);
        Query accented = query(List.of("é"), null, List.of(), ResultType.DATAFRAME, false);

        assertThat(CanonicalQuery.of(twoPaths)).isNotEqualTo(CanonicalQuery.of(onePathWithDelimiters));
        assertThat(CanonicalQuery.of(literalEscape)).isNotEqualTo(CanonicalQuery.of(accented));
        assertThat(CanonicalQuery.of(fullyPopulated()).chars()).allMatch(character -> character >= 0x20 && character <= 0x7e);
    }

    @Test
    void theCanonicalFormOfAFixedQueryNeverChanges() {
        assertThat(CanonicalQuery.of(fullyPopulated())).isEqualTo(GOLDEN_CANONICAL);
    }

    @Test
    void everyEnumConstantIsWrittenUnderItsPinnedName() {
        List<String> resultTypes = Arrays.stream(ResultType.values())
            .map(type -> writtenName(CanonicalQuery.of(query(List.of(), null, List.of(), type, false)), "expectedResultType")).toList();
        List<String> operators = Arrays.stream(Operator.values())
            .map(operator -> writtenName(CanonicalQuery.of(withClause(new PhenotypicSubquery(null, null, operator))), "operator")).toList();
        List<String> filterTypes = Arrays.stream(PhenotypicFilterType.values())
            .map(
                type -> writtenName(
                    CanonicalQuery.of(withClause(new PhenotypicFilter(type, null, null, null, null, null))), "phenotypicFilterType"
                )
            ).toList();

        assertThat(resultTypes).containsExactly(
            "COUNT", "DATAFRAME", "CROSS_COUNT", "CATEGORICAL_CROSS_COUNT", "CONTINUOUS_CROSS_COUNT", "INFO_COLUMN_LISTING",
            "OBSERVATION_CROSS_COUNT", "VARIANT_COUNT_FOR_QUERY", "VARIANT_LIST_FOR_QUERY", "VCF_EXCERPT", "AGGREGATE_VCF_EXCERPT",
            "DATAFRAME_TIMESERIES", "DATAFRAME_PFB", "PATIENTS"
        );
        assertThat(operators).containsExactly("AND", "OR");
        assertThat(filterTypes).containsExactly("REQUIRED", "FILTER", "ANY_RECORD_OF");
    }

    @Test
    void theIdOfAFixedUserAndQueryNeverChanges() {
        assertThat(QueryIdentity.idFor(USER, fullyPopulated())).hasToString(GOLDEN_ID);
    }

    @Test
    void generalIdIgnoresResultTypeAndSample() {
        Query dataframe = query(List.of(AGE), numeric(18.0, null), List.of(), ResultType.DATAFRAME, false);
        Query pfb = query(List.of(AGE), numeric(18.0, null), List.of(), ResultType.DATAFRAME_PFB, false);
        Query sample = query(List.of(AGE), numeric(18.0, null), List.of(), ResultType.DATAFRAME, true);
        Query pfbSample = query(List.of(AGE), numeric(18.0, null), List.of(), ResultType.DATAFRAME_PFB, true);
        Query untyped = query(List.of(AGE), numeric(18.0, null), List.of(), null, false);

        UUID expected = QueryIdentity.generalIdFor(USER, dataframe);

        assertThat(List.of(pfb, sample, pfbSample, untyped)).allSatisfy(variant -> {
            assertThat(CanonicalQuery.generalOf(variant)).isEqualTo(CanonicalQuery.generalOf(dataframe));
            assertThat(QueryIdentity.generalIdFor(USER, variant)).isEqualTo(expected);
            assertThat(QueryIdentity.idFor(USER, variant)).isNotEqualTo(QueryIdentity.idFor(USER, dataframe));
        });
        assertThat(CanonicalQuery.generalOf(pfbSample)).doesNotContain("expectedResultType", "sample", "DATAFRAME");
    }

    @Test
    void generalIdDiffersFromTheRowId() {
        List<Query> queries = new ArrayList<>(List.of(fullyPopulated(), new Query(null, null, null, null, null, null, null, false)));
        for (ResultType type : ResultType.values()) {
            queries.add(query(List.of(AGE), null, List.of(), type, false));
            queries.add(query(List.of(AGE), null, List.of(), type, true));
        }

        assertThat(queries).allSatisfy(query -> {
            assertThat(CanonicalQuery.generalOf(query)).isNotEqualTo(CanonicalQuery.of(query));
            assertThat(QueryIdentity.generalIdFor(USER, query)).isNotEqualTo(QueryIdentity.idFor(USER, query));
        });
    }

    @Test
    void generalIdChangesWithUserSelectOrderAndClause() {
        PhenotypicFilter sex = categorical(Set.of("Male"));
        PhenotypicFilter age = numeric(18.0, null);
        GenomicFilter gene = new GenomicFilter("Gene_with_variant", List.of("APOE"), null, null);
        Query base = query(List.of(AGE, SEX), all(sex, age), List.of(gene), ResultType.DATAFRAME, false);
        Query sameAgain = query(List.of(AGE, SEX), all(sex, age), List.of(gene), ResultType.DATAFRAME, false);
        Query otherSelectOrder = query(List.of(SEX, AGE), all(sex, age), List.of(gene), ResultType.DATAFRAME, false);
        Query otherClauseOrder = query(List.of(AGE, SEX), all(age, sex), List.of(gene), ResultType.DATAFRAME, false);
        Query otherClause = query(List.of(AGE, SEX), all(sex, numeric(21.0, null)), List.of(gene), ResultType.DATAFRAME, false);
        Query noClause = query(List.of(AGE, SEX), null, List.of(gene), ResultType.DATAFRAME, false);
        Query noGenomicFilter = query(List.of(AGE, SEX), all(sex, age), List.of(), ResultType.DATAFRAME, false);

        UUID general = QueryIdentity.generalIdFor(USER, base);

        assertThat(QueryIdentity.generalIdFor(USER, sameAgain)).isEqualTo(general);
        assertThat(QueryIdentity.generalIdFor(OTHER_USER, base)).isNotEqualTo(general);
        assertThat(QueryIdentity.generalIdFor(USER, otherSelectOrder)).isNotEqualTo(general);
        assertThat(QueryIdentity.generalIdFor(USER, otherClauseOrder)).isNotEqualTo(general);
        assertThat(QueryIdentity.generalIdFor(USER, otherClause)).isNotEqualTo(general);
        assertThat(QueryIdentity.generalIdFor(USER, noClause)).isNotEqualTo(general);
        assertThat(QueryIdentity.generalIdFor(USER, noGenomicFilter)).isNotEqualTo(general);
    }

    @Test
    void generalIdIgnoresAuthorizationFiltersIdAndPicsureId() {
        Query scoped = fullyPopulated();
        Query bare = new Query(
            scoped.select(), null, scoped.phenotypicClause(), scoped.genomicFilters(), scoped.expectedResultType(), null, null,
            scoped.sample()
        );

        assertThat(CanonicalQuery.generalOf(scoped)).isEqualTo(CanonicalQuery.generalOf(bare))
            .doesNotContain("phs000007", "8694e3d4", "11111111", "authorizationFilters");
        assertThat(QueryIdentity.generalIdFor(USER, scoped)).isEqualTo(QueryIdentity.generalIdFor(USER, bare));
    }

    @Test
    void generalFormIsTheRowFormWithoutItsLastTwoMembers() {
        String lastTwoMembers = ",\"expectedResultType\":\"DATAFRAME_PFB\",\"sample\":true}";
        Query smallest = new Query(null, null, null, null, null, null, null, false);

        assertThat(GOLDEN_CANONICAL).endsWith(lastTwoMembers);
        assertThat(CanonicalQuery.generalOf(fullyPopulated()))
            .isEqualTo(GOLDEN_CANONICAL.substring(0, GOLDEN_CANONICAL.length() - lastTwoMembers.length()) + "}");
        assertThat(CanonicalQuery.generalOf(smallest))
            .isEqualTo("{\"v\":" + CanonicalQuery.FORM_VERSION + ",\"select\":[],\"phenotypicClause\":null,\"genomicFilters\":[]}");
        assertThat(CanonicalQuery.of(smallest)).isEqualTo(
            "{\"v\":1,\"select\":[],\"phenotypicClause\":null,\"genomicFilters\":[],\"expectedResultType\":null,\"sample\":false}"
        );
        for (ResultType type : ResultType.values()) {
            for (boolean sample : new boolean[] {false, true}) {
                Query variant = query(List.of(SEX, AGE), all(numeric(18.0, null)), List.of(), type, sample);
                String general = CanonicalQuery.generalOf(variant);

                assertThat(CanonicalQuery.of(variant)).isEqualTo(
                    general.substring(0, general.length() - 1) + ",\"expectedResultType\":\"" + type.name() + "\",\"sample\":" + sample
                        + "}"
                );
            }
        }
    }

    @Test
    void theGeneralIdOfAFixedUserAndQueryNeverChanges() {
        assertThat(QueryIdentity.generalIdFor(USER, fullyPopulated())).hasToString(GOLDEN_GENERAL_ID);
    }

    @Test
    void theGeneralIdIsAVersion5UuidOfTheUserAndTheSha256OfTheGeneralForm() {
        Query query = fullyPopulated();
        byte[] name = digest("SHA-256", CanonicalQuery.generalOf(query).getBytes(StandardCharsets.UTF_8));

        UUID generalId = QueryIdentity.generalIdFor(USER, query);

        assertThat(generalId.version()).isEqualTo(5);
        assertThat(generalId.variant()).isEqualTo(2);
        assertThat(generalId).isEqualTo(rfc4122Version5(USER, name));
    }

    @Test
    void aMissingUserOrQueryIsRefusedForTheGeneralId() {
        assertThatThrownBy(() -> QueryIdentity.generalIdFor(null, fullyPopulated())).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> QueryIdentity.generalIdFor(USER, null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> CanonicalQuery.generalOf(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void theIdIsAVersion5UuidOfTheUserAndTheSha256OfTheCanonicalForm() {
        Query query = fullyPopulated();
        byte[] name = digest("SHA-256", CanonicalQuery.of(query).getBytes(StandardCharsets.UTF_8));

        UUID id = QueryIdentity.idFor(USER, query);

        assertThat(id.version()).isEqualTo(5);
        assertThat(id.variant()).isEqualTo(2);
        assertThat(id).isEqualTo(rfc4122Version5(USER, name));
    }

    @Test
    void aMissingUserOrQueryIsRefused() {
        assertThatThrownBy(() -> QueryIdentity.idFor(null, fullyPopulated())).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> QueryIdentity.idFor(USER, null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> CanonicalQuery.of(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void everyComponentOfEveryRecordInTheQueryTreeIsHandled() {
        Set<Class<?>> reachable = new HashSet<>();
        Deque<Class<?>> pending = new ArrayDeque<>(List.of(Query.class));
        while (!pending.isEmpty()) {
            Class<?> type = pending.pop();
            if (!reachable.add(type)) {
                continue;
            }
            if (type.isSealed()) {
                pending.addAll(List.of(type.getPermittedSubclasses()));
            }
            if (type.isRecord() && !CanonicalQuery.IGNORED_RECORDS.contains(type)) {
                Arrays.stream(type.getRecordComponents()).forEach(component -> pending.addAll(modelTypesIn(component.getGenericType())));
            }
        }
        Set<Class<?>> records = new HashSet<>();
        reachable.stream().filter(Class::isRecord).filter(type -> !CanonicalQuery.IGNORED_RECORDS.contains(type)).forEach(records::add);

        assertThat(new HashSet<Class<?>>(CanonicalQuery.HANDLED_COMPONENTS.keySet())).isEqualTo(records);
        CanonicalQuery.HANDLED_COMPONENTS.forEach((type, components) -> {
            assertThat(CanonicalQuery.declaredComponents(type)).as(type.getName()).isEqualTo(components);
        });
    }

    @Test
    void everyClauseSubtypeIsHandled() {
        assertThat(CanonicalQuery.HANDLED_CLAUSE_TYPES).containsExactlyInAnyOrder(PhenotypicClause.class.getPermittedSubclasses());
    }

    @Test
    void aRecordWhoseComponentsDoNotMatchTheHandledListIsRefused() {
        List<String> handled = CanonicalQuery.HANDLED_COMPONENTS.get(GenomicFilter.class);
        List<String> missingOne = handled.subList(0, handled.size() - 1);

        assertThatCode(() -> CanonicalQuery.requireHandled(GenomicFilter.class, handled)).doesNotThrowAnyException();
        assertThatThrownBy(() -> CanonicalQuery.requireHandled(GenomicFilter.class, missingOne)).isInstanceOf(IllegalStateException.class)
            .hasMessageContaining(GenomicFilter.class.getName()).hasMessageContaining("max");
    }

    @Test
    void aClauseHierarchyThatDoesNotMatchTheHandledSubtypesIsRefused() {
        assertThatThrownBy(() -> CanonicalQuery.requireHandledSubtypes(PhenotypicClause.class, Set.of(PhenotypicFilter.class)))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining(PhenotypicSubquery.class.getName());
    }

    private static List<Class<?>> modelTypesIn(Type type) {
        List<Class<?>> found = new ArrayList<>();
        if (type instanceof ParameterizedType parameterized) {
            for (Type argument : parameterized.getActualTypeArguments()) {
                found.addAll(modelTypesIn(argument));
            }
        } else if (type instanceof Class<?> raw && (raw.isRecord() || raw.isSealed())) {
            found.add(raw);
        }
        return found;
    }
}
