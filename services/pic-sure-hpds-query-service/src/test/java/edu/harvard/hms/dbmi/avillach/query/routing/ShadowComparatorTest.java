package edu.harvard.hms.dbmi.avillach.query.routing;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import edu.harvard.hms.dbmi.avillach.query.routing.ShadowComparator.Result;
import edu.harvard.hms.dbmi.avillach.query.routing.ShadowComparator.Verdict;

/** Covers each normalization of {@link ShadowComparator}: what it accepts as equal, and a real difference it still reports. */
class ShadowComparatorTest {

    private static Verdict compare(String type, String java, String rust) {
        return ShadowComparator.compare(
            type, 200, java.getBytes(StandardCharsets.UTF_8), new RustOutcome.Answered(200, rust.getBytes(StandardCharsets.UTF_8), null)
        );
    }

    @Test
    void identicalBodiesMatchWithNoNormalization() {
        assertThat(compare("CROSS_COUNT", "{\"a\":1}", "{\"a\":1}")).isEqualTo(new Verdict(Result.MATCH, ShadowComparator.NONE, null));
    }

    @ParameterizedTest
    @ValueSource(strings = {"COUNT", "VARIANT_COUNT_FOR_QUERY", "DATAFRAME"})
    void exactTypesReportAnyByteDifference(String type) {
        assertThat(ShadowComparator.ruleFor(type)).isEqualTo(ShadowComparator.EXACT);
        assertThat(compare(type, "{\"count\":1,\"message\":\"m\"}", "{\"message\":\"m\",\"count\":1}"))
            .isEqualTo(new Verdict(Result.MISMATCH, ShadowComparator.EXACT, null));
    }

    @ParameterizedTest
    @ValueSource(strings = {"CROSS_COUNT", "CATEGORICAL_CROSS_COUNT", "CONTINUOUS_CROSS_COUNT", "OBSERVATION_CROSS_COUNT"})
    void jsonKeyOrderIgnoresObjectKeyOrderOnly(String type) {
        assertThat(compare(type, "{\"\\\\a\\\\\":{\"x\":1,\"y\":2},\"\\\\b\\\\\":3}", "{\"\\\\b\\\\\":3,\"\\\\a\\\\\":{\"y\":2,\"x\":1}}"))
            .isEqualTo(new Verdict(Result.MATCH, ShadowComparator.JSON_KEY_ORDER, null));
        assertThat(compare(type, "{\"a\":1,\"b\":3}", "{\"b\":4,\"a\":1}"))
            .isEqualTo(new Verdict(Result.MISMATCH, ShadowComparator.JSON_KEY_ORDER, null));
        assertThat(compare(type, "{\"a\":[1,2]}", "{\"a\":[2,1]}")).extracting(Verdict::result).isEqualTo(Result.MISMATCH);
    }

    @Test
    void variantListIsAMultiset() {
        String java = "[19,44908684,T,C,APOE,missense_variant, 19,44908822,C,T,APOE,missense_variant]";
        String rust = "[19,44908822,C,T,APOE,missense_variant, 19,44908684,T,C,APOE,missense_variant]";
        assertThat(compare("VARIANT_LIST_FOR_QUERY", java, rust))
            .isEqualTo(new Verdict(Result.MATCH, ShadowComparator.VARIANT_LIST_ORDER, null));
        assertThat(compare("VARIANT_LIST_FOR_QUERY", "[a, a, b]", "[a, b, b]")).extracting(Verdict::result).isEqualTo(Result.MISMATCH);
        assertThat(compare("VARIANT_LIST_FOR_QUERY", "[]", " [] ")).extracting(Verdict::result).isEqualTo(Result.MATCH);
    }

    @Test
    void infoColumnListingIsAMultiset() {
        String java = "[{\"key\":\"A\",\"continuous\":false},{\"key\":\"B\",\"continuous\":true}]";
        String rust = "[{\"continuous\":true,\"key\":\"B\"},{\"key\":\"A\",\"continuous\":false}]";
        assertThat(compare("INFO_COLUMN_LISTING", java, rust))
            .isEqualTo(new Verdict(Result.MATCH, ShadowComparator.INFO_COLUMN_LISTING_ORDER, null));
        assertThat(compare("INFO_COLUMN_LISTING", java, "[{\"key\":\"A\",\"continuous\":false}]")).extracting(Verdict::result)
            .isEqualTo(Result.MISMATCH);
    }

    @ParameterizedTest
    @ValueSource(strings = {"VCF_EXCERPT", "AGGREGATE_VCF_EXCERPT"})
    void excerptRowsColumnsAndInfoCellsAreUnordered(String type) {
        String java = "CHROM\tPOSITION\tGene_with_variant\tp1\tp2\n19\t100\tAPOE,APOC1\t0/1\t1/1\n19\t200\tTOMM40\t0/0\t0/1\n";
        String rust = "CHROM\tPOSITION\tGene_with_variant\tp2\tp1\n19\t200\tTOMM40\t0/1\t0/0\n19\t100\tAPOC1,APOE\t1/1\t0/1\n";
        assertThat(compare(type, java, rust)).isEqualTo(new Verdict(Result.MATCH, ShadowComparator.EXCERPT_ORDER, null));
    }

    @Test
    void anExcerptWithACellMovedBetweenPatientsIsAMismatch() {
        String java = "CHROM\tp1\tp2\n19\t0/1\t1/1\n";
        String rust = "CHROM\tp1\tp2\n19\t1/1\t0/1\n";
        assertThat(compare("VCF_EXCERPT", java, rust)).extracting(Verdict::result).isEqualTo(Result.MISMATCH);
    }

    @Test
    void searchComparesConceptsCategoryValuesAndInfoValuesAsMultisets() {
        String java =
            "{\"searchQuery\":\"age\",\"results\":{\"phenotypes\":[{\"\\\\b\\\\\":{\"name\":\"b\",\"categoryValues\":[\"y\",\"x\"]}},"
                + "{\"\\\\a\\\\\":{\"name\":\"a\"}}],\"info\":{\"Gene_with_variant\":{\"values\":[\"APOE\",\"APOC1\"]}}}}";
        String rust = "{\"results\":{\"info\":{\"Gene_with_variant\":{\"values\":[\"APOC1\",\"APOE\"]}},\"phenotypes\":[{\"\\\\a\\\\\":"
            + "{\"name\":\"a\"}},{\"\\\\b\\\\\":{\"categoryValues\":[\"x\",\"y\"],\"name\":\"b\"}}]},\"searchQuery\":\"age\"}";
        assertThat(compare("SEARCH", java, rust)).isEqualTo(new Verdict(Result.MATCH, ShadowComparator.SEARCH, null));
        String changed = rust.replace("\"x\",\"y\"", "\"x\",\"z\"");
        assertThat(compare("SEARCH", java, changed)).isEqualTo(new Verdict(Result.MISMATCH, ShadowComparator.SEARCH, null));
    }

    @Test
    void searchComparesEveryFieldSoAnExtraFieldIsAMismatch() {
        String java = "{\"results\":{\"phenotypes\":[{\"\\\\a\\\\\":{\"name\":\"a\"}}]}}";
        String rust = "{\"results\":{\"phenotypes\":[{\"\\\\a\\\\\":{\"name\":\"a\",\"widthInBytes\":4}}]}}";
        assertThat(compare("SEARCH", java, rust)).isEqualTo(new Verdict(Result.MISMATCH, ShadowComparator.SEARCH, null));
    }

    @Test
    void searchValuesComparePageAndTotalExactlyAndResultsAsAMultiset() {
        String java = "{\"results\":[\"APOE\",\"APOC1\",\"TOMM40\"],\"page\":2,\"total\":30}";
        assertThat(ShadowComparator.ruleFor("SEARCH_VALUES")).isEqualTo(ShadowComparator.SEARCH_VALUES_BUCKET_ORDER);
        assertThat(compare("SEARCH_VALUES", java, "{\"page\":2,\"total\":30,\"results\":[\"TOMM40\",\"APOE\",\"APOC1\"]}"))
            .isEqualTo(new Verdict(Result.MATCH, ShadowComparator.SEARCH_VALUES_BUCKET_ORDER, null));
        assertThat(compare("SEARCH_VALUES", java, "{\"results\":[\"APOE\",\"APOC1\",\"TOMM40\"],\"page\":2,\"total\":31}"))
            .extracting(Verdict::result).isEqualTo(Result.MISMATCH);
        assertThat(compare("SEARCH_VALUES", java, "{\"results\":[\"APOE\",\"APOC1\",\"TOMM40\"],\"page\":3,\"total\":30}"))
            .extracting(Verdict::result).isEqualTo(Result.MISMATCH);
        assertThat(compare("SEARCH_VALUES", java, "{\"results\":[\"APOE\",\"APOE\",\"TOMM40\"],\"page\":2,\"total\":30}"))
            .extracting(Verdict::result).isEqualTo(Result.MISMATCH);
    }

    @Test
    void aTruncatedRustBodyIsNotCompared() {
        byte[] body = "1".getBytes(StandardCharsets.UTF_8);
        assertThat(ShadowComparator.compare("COUNT", 200, body, new RustOutcome.Answered(200, body, null, true)))
            .isEqualTo(new Verdict(Result.NOT_COMPARED, null, ShadowComparator.BODY_TOO_LARGE));
    }

    @Test
    void anUnparseableBodyNeverMatchesUnderAJsonRule() {
        assertThat(compare("CROSS_COUNT", "{\"a\":1}", "not json")).extracting(Verdict::result).isEqualTo(Result.MISMATCH);
    }

    @Test
    void twoNon2xxAnswersCompareByStatusOnly() {
        assertThat(
            ShadowComparator.compare("COUNT", 400, null, new RustOutcome.Answered(400, "other".getBytes(StandardCharsets.UTF_8), null))
        ).isEqualTo(new Verdict(Result.MATCH, ShadowComparator.STATUS_ONLY, null));
        assertThat(ShadowComparator.compare("COUNT", 500, null, new RustOutcome.Answered(400, new byte[0], null)))
            .isEqualTo(new Verdict(Result.MISMATCH, ShadowComparator.STATUS_ONLY, null));
        assertThat(
            ShadowComparator.compare("COUNT", 200, "1".getBytes(StandardCharsets.UTF_8), new RustOutcome.Answered(400, new byte[0], null))
        ).isEqualTo(new Verdict(Result.MISMATCH, ShadowComparator.STATUS_ONLY, null));
    }

    @Test
    void aRustTransportFailureIsNotCompared() {
        assertThat(
            ShadowComparator.compare("COUNT", 200, "1".getBytes(StandardCharsets.UTF_8), new RustOutcome.Failed(RustFailure.CONNECT_ERROR))
        ).isEqualTo(new Verdict(Result.NOT_COMPARED, null, "connect-error"));
    }

    @Test
    void aBodyOver16MibIsNotCompared() {
        byte[] large = new byte[ShadowComparator.MAX_COMPARED_BYTES + 1];
        assertThat(ShadowComparator.compare("COUNT", 200, large, new RustOutcome.Answered(200, large.clone(), null)))
            .isEqualTo(new Verdict(Result.NOT_COMPARED, null, ShadowComparator.BODY_TOO_LARGE));
        byte[] limit = new byte[ShadowComparator.MAX_COMPARED_BYTES];
        assertThat(ShadowComparator.compare("COUNT", 200, limit, new RustOutcome.Answered(200, limit.clone(), null)))
            .extracting(Verdict::result).isEqualTo(Result.MATCH);
    }
}
