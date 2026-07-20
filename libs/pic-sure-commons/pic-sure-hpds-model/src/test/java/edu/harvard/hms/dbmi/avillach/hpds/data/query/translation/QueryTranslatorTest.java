package edu.harvard.hms.dbmi.avillach.hpds.data.query.translation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import edu.harvard.hms.dbmi.avillach.hpds.data.query.Filter.DoubleFilter;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.Filter.FloatFilter;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.Query;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.Query.VariantInfoFilter;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.ResultType;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.GenomicFilter;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.Operator;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.PhenotypicClause;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.PhenotypicFilter;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.PhenotypicFilterType;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.PhenotypicSubquery;

class QueryTranslatorTest {

    private static Query v1() {
        return new Query();
    }

    private static VariantInfoFilter group(Map<String, String[]> cat, Map<String, FloatFilter> num) {
        VariantInfoFilter g = new VariantInfoFilter();
        g.categoryVariantInfoFilters = cat;
        g.numericVariantInfoFilters = num;
        return g;
    }

    private static PhenotypicFilter asFilter(PhenotypicClause c) {
        return (PhenotypicFilter) c;
    }

    private static PhenotypicSubquery asSub(PhenotypicClause c) {
        return (PhenotypicSubquery) c;
    }

    // ---------- select assembly ----------

    @Test
    void selectFromFieldsOnly() throws Exception {
        Query q = v1();
        q.setFields(List.of("\\a\\", "\\b\\"));
        assertThat(QueryTranslator.translate(q).select()).containsExactly("\\a\\", "\\b\\");
    }

    @Test
    void selectFromCrossCountFieldsOnly() throws Exception {
        Query q = v1();
        q.setCrossCountFields(List.of("\\c\\"));
        assertThat(QueryTranslator.translate(q).select()).containsExactly("\\c\\");
    }

    @Test
    void selectDedupesFieldsFirstThenCrossCount() throws Exception {
        Query q = v1();
        q.setFields(List.of("\\a\\", "\\b\\"));
        q.setCrossCountFields(List.of("\\b\\", "\\d\\"));
        assertThat(QueryTranslator.translate(q).select()).containsExactly("\\a\\", "\\b\\", "\\d\\");
    }

    @Test
    void selectEmptyWhenNoFields() throws Exception {
        assertThat(QueryTranslator.translate(v1()).select()).isEmpty();
    }

    // ---------- empty / trivial ----------

    @Test
    void emptyQueryProducesEmptyV3() throws Exception {
        edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.Query out = QueryTranslator.translate(v1());
        assertThat(out.phenotypicClause()).isNull();
        assertThat(out.genomicFilters()).isEmpty();
        assertThat(out.select()).isEmpty();
        assertThat(out.authorizationFilters()).isEmpty();
        assertThat(out.expectedResultType()).isEqualTo(ResultType.COUNT); // v1 default
        assertThat(out.id()).isNull();
        assertThat(out.picsureId()).isNull();
    }

    @Test
    void expectedResultTypeCarriedThrough() throws Exception {
        Query q = v1();
        q.setExpectedResultType(ResultType.DATAFRAME);
        assertThat(QueryTranslator.translate(q).expectedResultType()).isEqualTo(ResultType.DATAFRAME);
    }

    @Test
    void expectedResultTypeNullStaysNull() throws Exception {
        Query q = v1();
        q.setExpectedResultType(null);
        assertThat(QueryTranslator.translate(q).expectedResultType()).isNull();
    }

    // ---------- single-clause collapse ----------

    @Test
    void singleCategoryFilterCollapsesToBareFilter() throws Exception {
        Query q = v1();
        q.setCategoryFilters(Map.of("\\sex\\", new String[] {"M", "F"}));
        PhenotypicFilter f = asFilter(QueryTranslator.translate(q).phenotypicClause());
        assertThat(f.phenotypicFilterType()).isEqualTo(PhenotypicFilterType.FILTER);
        assertThat(f.conceptPath()).isEqualTo("\\sex\\");
        assertThat(f.values()).containsExactly("M", "F");
        assertThat(f.min()).isNull();
        assertThat(f.max()).isNull();
        assertThat(f.not()).isFalse();
    }

    @Test
    void singleNumericFilterMinAndMax() throws Exception {
        Query q = v1();
        q.setNumericFilters(Map.of("\\age\\", new DoubleFilter(1.0, 9.0)));
        PhenotypicFilter f = asFilter(QueryTranslator.translate(q).phenotypicClause());
        assertThat(f.phenotypicFilterType()).isEqualTo(PhenotypicFilterType.FILTER);
        assertThat(f.values()).isNull();
        assertThat(f.min()).isEqualTo(1.0);
        assertThat(f.max()).isEqualTo(9.0);
    }

    @Test
    void singleNumericFilterMinOnly() throws Exception {
        Query q = v1();
        q.setNumericFilters(Map.of("\\age\\", new DoubleFilter(5.0, null)));
        PhenotypicFilter f = asFilter(QueryTranslator.translate(q).phenotypicClause());
        assertThat(f.min()).isEqualTo(5.0);
        assertThat(f.max()).isNull();
    }

    @Test
    void singleNumericFilterMaxOnly() throws Exception {
        Query q = v1();
        q.setNumericFilters(Map.of("\\age\\", new DoubleFilter(null, 5.0)));
        PhenotypicFilter f = asFilter(QueryTranslator.translate(q).phenotypicClause());
        assertThat(f.min()).isNull();
        assertThat(f.max()).isEqualTo(5.0);
    }

    @Test
    void singleRequiredField() throws Exception {
        Query q = v1();
        q.setRequiredFields(List.of("\\req\\"));
        PhenotypicFilter f = asFilter(QueryTranslator.translate(q).phenotypicClause());
        assertThat(f.phenotypicFilterType()).isEqualTo(PhenotypicFilterType.REQUIRED);
        assertThat(f.conceptPath()).isEqualTo("\\req\\");
        assertThat(f.values()).isNull();
        assertThat(f.min()).isNull();
        assertThat(f.max()).isNull();
    }

    @Test
    void singlePathAnyRecordOfCollapsesToBareFilter() throws Exception {
        Query q = v1();
        q.setAnyRecordOf(List.of("\\only\\"));
        PhenotypicFilter f = asFilter(QueryTranslator.translate(q).phenotypicClause());
        assertThat(f.phenotypicFilterType()).isEqualTo(PhenotypicFilterType.ANY_RECORD_OF);
        assertThat(f.conceptPath()).isEqualTo("\\only\\");
    }

    // ---------- multi-clause AND ----------

    @Test
    void twoCategoryFiltersFormAndSubquerySortedByPath() throws Exception {
        Query q = v1();
        Map<String, String[]> cats = new TreeMap<>();
        cats.put("\\zeta\\", new String[] {"1"});
        cats.put("\\alpha\\", new String[] {"2"});
        q.setCategoryFilters(cats);
        PhenotypicSubquery sub = asSub(QueryTranslator.translate(q).phenotypicClause());
        assertThat(sub.operator()).isEqualTo(Operator.AND);
        assertThat(sub.not()).isFalse();
        assertThat(sub.phenotypicClauses()).hasSize(2);
        assertThat(asFilter(sub.phenotypicClauses().get(0)).conceptPath()).isEqualTo("\\alpha\\");
        assertThat(asFilter(sub.phenotypicClauses().get(1)).conceptPath()).isEqualTo("\\zeta\\");
    }

    @Test
    void oneOfEachFamilyFormsAndSubqueryInFamilyOrder() throws Exception {
        Query q = v1();
        q.setCategoryFilters(Map.of("\\cat\\", new String[] {"x"}));
        q.setNumericFilters(Map.of("\\num\\", new DoubleFilter(1.0, 2.0)));
        q.setRequiredFields(List.of("\\req\\"));
        q.setAnyRecordOf(List.of("\\aro\\"));
        PhenotypicSubquery sub = asSub(QueryTranslator.translate(q).phenotypicClause());
        assertThat(sub.operator()).isEqualTo(Operator.AND);
        List<PhenotypicClause> cs = sub.phenotypicClauses();
        assertThat(cs).hasSize(4);
        assertThat(asFilter(cs.get(0)).phenotypicFilterType()).isEqualTo(PhenotypicFilterType.FILTER);
        assertThat(asFilter(cs.get(0)).conceptPath()).isEqualTo("\\cat\\");
        assertThat(asFilter(cs.get(1)).phenotypicFilterType()).isEqualTo(PhenotypicFilterType.FILTER);
        assertThat(asFilter(cs.get(1)).conceptPath()).isEqualTo("\\num\\");
        assertThat(asFilter(cs.get(2)).phenotypicFilterType()).isEqualTo(PhenotypicFilterType.REQUIRED);
        assertThat(asFilter(cs.get(3)).phenotypicFilterType()).isEqualTo(PhenotypicFilterType.ANY_RECORD_OF);
    }

    @Test
    void categoryFilterWithEmptyValuesIsStillEmitted() throws Exception {
        Query q = v1();
        q.setCategoryFilters(Map.of("\\empty\\", new String[] {}));
        PhenotypicFilter f = asFilter(QueryTranslator.translate(q).phenotypicClause());
        assertThat(f.phenotypicFilterType()).isEqualTo(PhenotypicFilterType.FILTER);
        assertThat(f.values()).isEmpty();
    }

    // ---------- any-record-of OR/AND ----------

    @Test
    void anyRecordOfMultiPathBecomesOrSubqueryAsTopLevel() throws Exception {
        Query q = v1();
        q.setAnyRecordOf(List.of("\\a\\", "\\b\\"));
        PhenotypicSubquery sub = asSub(QueryTranslator.translate(q).phenotypicClause());
        assertThat(sub.operator()).isEqualTo(Operator.OR);
        assertThat(sub.phenotypicClauses()).hasSize(2);
        assertThat(asFilter(sub.phenotypicClauses().get(0)).phenotypicFilterType()).isEqualTo(PhenotypicFilterType.ANY_RECORD_OF);
        assertThat(asFilter(sub.phenotypicClauses().get(0)).conceptPath()).isEqualTo("\\a\\");
        assertThat(asFilter(sub.phenotypicClauses().get(1)).conceptPath()).isEqualTo("\\b\\");
    }

    @Test
    void anyRecordOfMultiOnlyBecomesOrSubquery() throws Exception {
        Query q = v1();
        q.setAnyRecordOfMulti(List.of(List.of("\\c\\", "\\d\\")));
        PhenotypicSubquery sub = asSub(QueryTranslator.translate(q).phenotypicClause());
        assertThat(sub.operator()).isEqualTo(Operator.OR);
        assertThat(sub.phenotypicClauses()).hasSize(2);
    }

    @Test
    void anyRecordOfPlusMultiFormsAndOfOrs() throws Exception {
        Query q = v1();
        q.setAnyRecordOf(List.of("\\a\\", "\\b\\"));
        q.setAnyRecordOfMulti(List.of(List.of("\\c\\", "\\d\\")));
        PhenotypicSubquery top = asSub(QueryTranslator.translate(q).phenotypicClause());
        assertThat(top.operator()).isEqualTo(Operator.AND);
        assertThat(top.phenotypicClauses()).hasSize(2);
        // anyRecordOf group first
        PhenotypicSubquery first = asSub(top.phenotypicClauses().get(0));
        PhenotypicSubquery second = asSub(top.phenotypicClauses().get(1));
        assertThat(first.operator()).isEqualTo(Operator.OR);
        assertThat(asFilter(first.phenotypicClauses().get(0)).conceptPath()).isEqualTo("\\a\\");
        assertThat(second.operator()).isEqualTo(Operator.OR);
        assertThat(asFilter(second.phenotypicClauses().get(0)).conceptPath()).isEqualTo("\\c\\");
    }

    @Test
    void anyRecordOfMultiEmptyInnerListIsSkipped() throws Exception {
        Query q = v1();
        q.setAnyRecordOf(List.of("\\a\\"));
        q.setAnyRecordOfMulti(List.of(new ArrayList<>()));
        // only the single-path anyRecordOf survives -> bare filter, no AND wrapper
        PhenotypicFilter f = asFilter(QueryTranslator.translate(q).phenotypicClause());
        assertThat(f.conceptPath()).isEqualTo("\\a\\");
    }

    @Test
    void anyRecordOfMultiSinglePathInnerListIsBareFilterInsideAnd() throws Exception {
        Query q = v1();
        q.setCategoryFilters(Map.of("\\cat\\", new String[] {"x"}));
        q.setAnyRecordOfMulti(List.of(List.of("\\x\\")));
        PhenotypicSubquery top = asSub(QueryTranslator.translate(q).phenotypicClause());
        assertThat(top.operator()).isEqualTo(Operator.AND);
        assertThat(top.phenotypicClauses()).hasSize(2);
        assertThat(asFilter(top.phenotypicClauses().get(1)).phenotypicFilterType()).isEqualTo(PhenotypicFilterType.ANY_RECORD_OF);
        assertThat(asFilter(top.phenotypicClauses().get(1)).conceptPath()).isEqualTo("\\x\\");
    }

    /**
     * Pins the review question (ramari16, PR #265): even when the list is an ancestor plus its own descendants -- the one case where
     * collapsing to the highest-level path WOULD be safe under v3 HPDS's match-all-below semantics -- the translator keeps the full
     * one-filter-per-path expansion. Redundant, but uniformly faithful.
     */
    @Test
    void anyRecordOfAncestorPlusDescendantsKeepsFullExpansion() throws Exception {
        Query q = v1();
        q.setAnyRecordOf(List.of("\\lab\\", "\\lab\\blood\\", "\\lab\\blood\\type\\"));
        PhenotypicSubquery sub = asSub(QueryTranslator.translate(q).phenotypicClause());
        assertThat(sub.operator()).isEqualTo(Operator.OR);
        assertThat(sub.phenotypicClauses()).hasSize(3);
        assertThat(sub.phenotypicClauses()).allSatisfy(c -> {
            assertThat(asFilter(c).phenotypicFilterType()).isEqualTo(PhenotypicFilterType.ANY_RECORD_OF);
            assertThat(asFilter(c).values()).isNull();
            assertThat(asFilter(c).min()).isNull();
            assertThat(asFilter(c).max()).isNull();
        });
        assertThat(sub.phenotypicClauses()).extracting(c -> asFilter(c).conceptPath())
            .containsExactly("\\lab\\", "\\lab\\blood\\", "\\lab\\blood\\type\\");
    }

    /**
     * The reason the expansion above cannot be replaced by a highest-level-path collapse: v1 lists may span unrelated branches, where no
     * single path covers the union. Each unrelated path must survive as its own OR'd filter.
     */
    @Test
    void anyRecordOfUnrelatedBranchesEachGetOwnFilter() throws Exception {
        Query q = v1();
        q.setAnyRecordOf(List.of("\\demographics\\age\\", "\\lab\\blood\\", "\\imaging\\mri\\"));
        PhenotypicSubquery sub = asSub(QueryTranslator.translate(q).phenotypicClause());
        assertThat(sub.operator()).isEqualTo(Operator.OR);
        assertThat(sub.phenotypicClauses()).extracting(c -> asFilter(c).conceptPath())
            .containsExactly("\\demographics\\age\\", "\\lab\\blood\\", "\\imaging\\mri\\");
    }

    @Test
    void anyRecordOfMultiNullInnerListIsSkipped() throws Exception {
        Query q = v1();
        List<List<String>> multi = new ArrayList<>();
        multi.add(null);
        multi.add(List.of("\\kept\\"));
        q.setAnyRecordOfMulti(multi);
        // null group skipped -> only the single-path group survives -> bare filter, no wrapper
        PhenotypicFilter f = asFilter(QueryTranslator.translate(q).phenotypicClause());
        assertThat(f.phenotypicFilterType()).isEqualTo(PhenotypicFilterType.ANY_RECORD_OF);
        assertThat(f.conceptPath()).isEqualTo("\\kept\\");
    }

    @Test
    void multipleAnyRecordOfMultiGroupsFormAndOfOrsInListOrder() throws Exception {
        Query q = v1();
        q.setAnyRecordOfMulti(List.of(List.of("\\g1a\\", "\\g1b\\"), List.of("\\g2a\\", "\\g2b\\")));
        PhenotypicSubquery top = asSub(QueryTranslator.translate(q).phenotypicClause());
        assertThat(top.operator()).isEqualTo(Operator.AND);
        assertThat(top.phenotypicClauses()).hasSize(2);
        PhenotypicSubquery first = asSub(top.phenotypicClauses().get(0));
        PhenotypicSubquery second = asSub(top.phenotypicClauses().get(1));
        assertThat(first.operator()).isEqualTo(Operator.OR);
        assertThat(first.phenotypicClauses()).extracting(c -> asFilter(c).conceptPath()).containsExactly("\\g1a\\", "\\g1b\\");
        assertThat(second.operator()).isEqualTo(Operator.OR);
        assertThat(second.phenotypicClauses()).extracting(c -> asFilter(c).conceptPath()).containsExactly("\\g2a\\", "\\g2b\\");
    }

    @Test
    void anyRecordOfGroupCombinesWithCategoryUnderAnd() throws Exception {
        Query q = v1();
        q.setCategoryFilters(Map.of("\\cat\\", new String[] {"x"}));
        q.setAnyRecordOf(List.of("\\a\\", "\\b\\"));
        PhenotypicSubquery top = asSub(QueryTranslator.translate(q).phenotypicClause());
        assertThat(top.operator()).isEqualTo(Operator.AND);
        assertThat(asFilter(top.phenotypicClauses().get(0)).phenotypicFilterType()).isEqualTo(PhenotypicFilterType.FILTER);
        assertThat(asSub(top.phenotypicClauses().get(1)).operator()).isEqualTo(Operator.OR);
    }

    @Test
    void categoryFilterWithNullValuesArrayYieldsNullValues() throws Exception {
        Query q = v1();
        Map<String, String[]> cats = new TreeMap<>();
        cats.put("\\nullvals\\", null);
        q.setCategoryFilters(cats);
        PhenotypicFilter f = asFilter(QueryTranslator.translate(q).phenotypicClause());
        assertThat(f.phenotypicFilterType()).isEqualTo(PhenotypicFilterType.FILTER);
        assertThat(f.values()).isNull();
    }

    @Test
    void categoryValuesAreDedupedPreservingFirstOccurrenceOrder() throws Exception {
        Query q = v1();
        q.setCategoryFilters(Map.of("\\sex\\", new String[] {"M", "F", "M"}));
        PhenotypicFilter f = asFilter(QueryTranslator.translate(q).phenotypicClause());
        assertThat(f.values()).containsExactly("M", "F");
    }

    // ---------- genomic ----------

    @Test
    void oneGroupOneCategoryKey() throws Exception {
        Query q = v1();
        q.setVariantInfoFilters(List.of(group(Map.of("Gene_with_variant", new String[] {"APOE"}), null)));
        List<GenomicFilter> gfs = QueryTranslator.translate(q).genomicFilters();
        assertThat(gfs).hasSize(1);
        assertThat(gfs.get(0).key()).isEqualTo("Gene_with_variant");
        assertThat(gfs.get(0).values()).containsExactly("APOE");
        assertThat(gfs.get(0).min()).isNull();
        assertThat(gfs.get(0).max()).isNull();
    }

    @Test
    void oneGroupOneNumericKey() throws Exception {
        Query q = v1();
        q.setVariantInfoFilters(List.of(group(null, Map.of("freq", new FloatFilter(0.1f, 0.9f)))));
        List<GenomicFilter> gfs = QueryTranslator.translate(q).genomicFilters();
        assertThat(gfs).hasSize(1);
        assertThat(gfs.get(0).key()).isEqualTo("freq");
        assertThat(gfs.get(0).values()).isNull();
        assertThat(gfs.get(0).min()).isEqualTo(0.1f);
        assertThat(gfs.get(0).max()).isEqualTo(0.9f);
    }

    @Test
    void oneGroupMixedKeysCategoryBeforeNumericEachSorted() throws Exception {
        Query q = v1();
        Map<String, String[]> cat = new TreeMap<>();
        cat.put("Zebra", new String[] {"z"});
        cat.put("Alpha", new String[] {"a"});
        Map<String, FloatFilter> num = new TreeMap<>();
        num.put("nZeta", new FloatFilter(1f, 2f));
        num.put("nAlpha", new FloatFilter(3f, 4f));
        q.setVariantInfoFilters(List.of(group(cat, num)));
        List<GenomicFilter> gfs = QueryTranslator.translate(q).genomicFilters();
        assertThat(gfs).extracting(GenomicFilter::key).containsExactly("Alpha", "Zebra", "nAlpha", "nZeta");
    }

    @Test
    void arbitraryGenomicKeyTranslatesGenerically() throws Exception {
        Query q = v1();
        q.setVariantInfoFilters(List.of(group(Map.of("Variant_severity", new String[] {"HIGH"}), null)));
        List<GenomicFilter> gfs = QueryTranslator.translate(q).genomicFilters();
        assertThat(gfs).hasSize(1);
        assertThat(gfs.get(0).key()).isEqualTo("Variant_severity");
    }

    @Test
    void numericGenomicMinOnly() throws Exception {
        Query q = v1();
        q.setVariantInfoFilters(List.of(group(null, Map.of("freq", new FloatFilter(0.2f, null)))));
        GenomicFilter gf = QueryTranslator.translate(q).genomicFilters().get(0);
        assertThat(gf.min()).isEqualTo(0.2f);
        assertThat(gf.max()).isNull();
    }

    @Test
    void nullVariantInfoFiltersYieldsEmptyGenomic() throws Exception {
        Query q = v1();
        q.setVariantInfoFilters(null);
        assertThat(QueryTranslator.translate(q).genomicFilters()).isEmpty();
    }

    @Test
    void emptyGroupListYieldsEmptyGenomic() throws Exception {
        Query q = v1();
        q.setVariantInfoFilters(List.of());
        assertThat(QueryTranslator.translate(q).genomicFilters()).isEmpty();
    }

    @Test
    void groupWithBothMapsNullCountsAsEmpty() throws Exception {
        Query q = v1();
        q.setVariantInfoFilters(List.of(group(null, null)));
        assertThat(QueryTranslator.translate(q).genomicFilters()).isEmpty();
    }

    @Test
    void oneEmptyOneNonEmptyGroupTranslates() throws Exception {
        Query q = v1();
        q.setVariantInfoFilters(List.of(group(Map.of(), Map.of()), group(Map.of("Gene_with_variant", new String[] {"APOE"}), null)));
        List<GenomicFilter> gfs = QueryTranslator.translate(q).genomicFilters();
        assertThat(gfs).hasSize(1);
        assertThat(gfs.get(0).key()).isEqualTo("Gene_with_variant");
    }

    @Test
    void twoNonEmptyGroupsThrow() {
        Query q = v1();
        q.setVariantInfoFilters(
            List.of(
                group(Map.of("Gene_with_variant", new String[] {"A"}), null), group(Map.of("Gene_with_variant", new String[] {"B"}), null)
            )
        );
        assertThatThrownBy(() -> QueryTranslator.translate(q)).isInstanceOf(UntranslatableQueryException.class)
            .hasMessageContaining("multiple");
    }

    @Test
    void twoNonEmptyDuplicateGroupsStillThrow() {
        Query q = v1();
        String[] same = {"A"};
        q.setVariantInfoFilters(List.of(group(Map.of("Gene_with_variant", same), null), group(Map.of("Gene_with_variant", same), null)));
        assertThatThrownBy(() -> QueryTranslator.translate(q)).isInstanceOf(UntranslatableQueryException.class);
    }

    // ---------- ids ----------

    @Test
    void validUuidsAreParsed() throws Exception {
        Query q = v1();
        UUID id = UUID.randomUUID();
        UUID pid = UUID.randomUUID();
        q.setId(id.toString());
        q.setPicSureId(pid.toString());
        edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.Query out = QueryTranslator.translate(q);
        assertThat(out.id()).isEqualTo(id);
        assertThat(out.picsureId()).isEqualTo(pid);
    }

    @Test
    void nonUuidIdsBecomeNull() throws Exception {
        Query q = v1();
        q.setId("12345");
        q.setPicSureId("not-a-uuid");
        edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.Query out = QueryTranslator.translate(q);
        assertThat(out.id()).isNull();
        assertThat(out.picsureId()).isNull();
    }

    @Test
    void nullIdsStayNull() throws Exception {
        edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.Query out = QueryTranslator.translate(v1());
        assertThat(out.id()).isNull();
        assertThat(out.picsureId()).isNull();
    }

    // ---------- kitchen sink ----------

    @Test
    void kitchenSinkStructuralEquality() throws Exception {
        Query q = v1();
        q.setFields(List.of("\\exp1\\"));
        q.setCrossCountFields(List.of("\\cc1\\"));
        q.setCategoryFilters(Map.of("\\sex\\", new String[] {"M"}));
        q.setNumericFilters(Map.of("\\age\\", new DoubleFilter(0.0, 100.0)));
        q.setRequiredFields(List.of("\\req\\"));
        q.setAnyRecordOfMulti(List.of(List.of("\\aro1\\", "\\aro2\\")));
        q.setVariantInfoFilters(List.of(group(Map.of("Gene_with_variant", new String[] {"APOE"}), null)));
        q.setExpectedResultType(ResultType.DATAFRAME);

        edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.Query out = QueryTranslator.translate(q);

        assertThat(out.select()).containsExactly("\\exp1\\", "\\cc1\\");
        assertThat(out.expectedResultType()).isEqualTo(ResultType.DATAFRAME);
        assertThat(out.genomicFilters()).hasSize(1);
        PhenotypicSubquery top = asSub(out.phenotypicClause());
        assertThat(top.operator()).isEqualTo(Operator.AND);
        // 3 leaf filters (cat, num, req) + 1 OR subquery = 4 children
        assertThat(top.phenotypicClauses()).hasSize(4);
        assertThat(asSub(top.phenotypicClauses().get(3)).operator()).isEqualTo(Operator.OR);
    }

    @Test
    void jacksonRoundTripFromRawJson() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        String rawV1 = "{\"expectedResultType\":\"COUNT\",\"categoryFilters\":{\"\\\\sex\\\\\":[\"M\"]},"
            + "\"numericFilters\":{\"\\\\age\\\\\":{\"min\":1.0,\"max\":2.0}},\"fields\":[\"\\\\f\\\\\"]}";
        Query q = mapper.readValue(rawV1, Query.class);
        edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.Query out = QueryTranslator.translate(q);
        assertThat(out.select()).containsExactly("\\f\\");
        PhenotypicSubquery top = asSub(out.phenotypicClause());
        assertThat(top.phenotypicClauses()).hasSize(2);
        // round-trips through Jackson without throwing
        assertDoesNotThrow(() -> mapper.writeValueAsString(out));
    }
}
