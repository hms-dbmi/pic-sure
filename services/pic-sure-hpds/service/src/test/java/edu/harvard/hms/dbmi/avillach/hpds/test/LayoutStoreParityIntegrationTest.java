package edu.harvard.hms.dbmi.avillach.hpds.test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.ResultType;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.AuthorizationFilter;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.Operator;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.PhenotypicClause;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.PhenotypicFilter;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.PhenotypicFilterType;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.PhenotypicSubquery;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.Query;
import edu.harvard.hms.dbmi.avillach.hpds.processing.v3.CountV3Processor;
import edu.harvard.hms.dbmi.avillach.hpds.processing.v3.PhenotypicCountProcessor;
import edu.harvard.hms.dbmi.avillach.hpds.processing.v3.layout.LayoutConcept;
import edu.harvard.hms.dbmi.avillach.hpds.processing.v3.layout.LayoutCountV3Processor;
import edu.harvard.hms.dbmi.avillach.hpds.processing.v3.layout.LayoutPhenotypicObservationStore;
import edu.harvard.hms.dbmi.avillach.hpds.processing.v3.layout.LayoutPhenotypicQueryExecutor;
import edu.harvard.hms.dbmi.avillach.hpds.test.util.BuildIntegrationTestEnvironment;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Checks that the layout store, built by the Rust ETL from the same {@code allConcepts.csv} the javabin integration store is built from,
 * answers every v3 sync count type exactly as {@link CountV3Processor} does on the javabin store. Results are compared as the JSON the
 * service would send, key order included except for CROSS_COUNT, whose javabin map is unordered.
 *
 * <p>The fixture in {@code src/test/resources/layout/1000genomes} was written by
 * {@code hpds-etl load --mode new-search --key-file src/test/resources/phenotypic/encryption_key} from {@code phenotypic/allConcepts.csv}
 * with the header line {@code PATIENT_NUM,CONCEPT_PATH,NVAL_NUM,TVAL_CHAR,TIMESTAMP} prepended. The CSV has no header, and the new-search
 * loader reads its first line as one, while {@code CSVLoader}, which builds the javabin store, keeps it as data.
 */
@ExtendWith(SpringExtension.class)
@EnableAutoConfiguration
@SpringBootTest(classes = edu.harvard.hms.dbmi.avillach.hpds.service.HpdsApplication.class)
@ActiveProfiles("integration-test")
public class LayoutStoreParityIntegrationTest {

    private static final Path LAYOUT_STORE = Path.of("src/test/resources/layout/1000genomes");

    private static final Path GOLDEN = Path.of("../../pic-sure-hpds-rs/bench/golden/1000genomes.json");

    private static final String PREFIX = "\\open_access-1000Genomes\\data\\";

    private static final String SEX = PREFIX + "SEX\\";

    private static final String AGE = PREFIX + "SYNTHETIC_AGE\\";

    private static final String HEIGHT = PREFIX + "SYNTHETIC_HEIGHT\\";

    private static final String POPULATION = PREFIX + "POPULATION NAME\\";

    private static final String SUPERPOPULATION = PREFIX + "SUPERPOPULATION CODE\\";

    private static final String UNKNOWN = PREFIX + "NOT_REAL_DOESNT_EXIST\\";

    private static final String FIRST_ROW_CONCEPT = PREFIX + "1000 GENOMES 30X ON GRCH38\\";

    private static final List<ResultType> SYNC_TYPES = List.of(
        ResultType.COUNT, ResultType.CROSS_COUNT, ResultType.CATEGORICAL_CROSS_COUNT, ResultType.CONTINUOUS_CROSS_COUNT,
        ResultType.OBSERVATION_CROSS_COUNT
    );

    private static LayoutPhenotypicObservationStore layoutStore;

    private static LayoutCountV3Processor layoutCounts;

    private final ObjectMapper mapper = new ObjectMapper();

    @Autowired
    private CountV3Processor javabinCounts;

    @BeforeAll
    public static void beforeAll() {
        BuildIntegrationTestEnvironment instance = BuildIntegrationTestEnvironment.INSTANCE;
        layoutStore = new LayoutPhenotypicObservationStore(LAYOUT_STORE, LAYOUT_STORE.resolve("encryption_key"), 64L << 20);
        layoutCounts = new LayoutCountV3Processor(new LayoutPhenotypicQueryExecutor(layoutStore), layoutStore);
    }

    @AfterAll
    public static void afterAll() throws IOException {
        layoutStore.close();
    }

    @Test
    public void everySyncTypeMatchesJavabinOnHandWrittenQueries() throws IOException {
        List<String> sexValues = categories(SEX);
        List<String> populationValues = categories(POPULATION);
        List<String> select = List.of(SEX, AGE, HEIGHT, POPULATION, FIRST_ROW_CONCEPT, UNKNOWN);
        List<PhenotypicClause> clauses = new ArrayList<>();
        clauses.add(null);
        clauses.add(filter(SEX, Set.of(sexValues.get(0))));
        clauses.add(filter(SEX, Set.of(sexValues.get(0), "not a category")));
        clauses.add(filter(POPULATION, Set.copyOf(populationValues.subList(0, 3))));
        clauses.add(range(AGE, 30.0, 50.0));
        clauses.add(range(AGE, null, 40.0));
        clauses.add(range(HEIGHT, 160.0, null));
        clauses.add(range(AGE, 50.0, 50.0));
        clauses.add(required(AGE));
        clauses.add(required(POPULATION));
        clauses.add(required(UNKNOWN));
        clauses.add(anyRecordOf(PREFIX));
        clauses.add(anyRecordOf(PREFIX + "SYNTHETIC_"));
        clauses.add(filter(UNKNOWN, Set.of("x")));
        clauses.add(range(UNKNOWN, 1.0, 2.0));
        clauses.add(subquery(Operator.AND, filter(SEX, Set.of(sexValues.get(1))), range(AGE, 20.0, 60.0), required(HEIGHT)));
        clauses.add(subquery(Operator.OR, filter(SEX, Set.of(sexValues.get(0))), range(AGE, 55.0, 62.0)));
        clauses.add(
            subquery(
                Operator.AND, subquery(Operator.OR, range(AGE, 31.0, 35.0), range(AGE, 55.0, 62.0)),
                subquery(Operator.OR, filter(POPULATION, Set.of(populationValues.get(0))), required(SUPERPOPULATION))
            )
        );

        assertEquals(4978, layoutCounts.runCounts(new Query(select, List.of(), null, List.of(), ResultType.COUNT, null, null)));
        assertEquals(
            sexValues.size(),
            layoutCounts.runCategoryCrossCounts(new Query(select, List.of(), required(SEX), List.of(), ResultType.COUNT, null, null))
                .get(SEX).size()
        );

        int compared = 0;
        for (PhenotypicClause clause : clauses) {
            for (ResultType type : SYNC_TYPES) {
                assertSameAnswer(new Query(select, List.of(), clause, List.of(), type, null, null));
                compared++;
            }
        }
        for (ResultType type : SYNC_TYPES) {
            List<AuthorizationFilter> authorization = List.of(new AuthorizationFilter(SEX, Set.of(sexValues.get(0))));
            assertSameAnswer(new Query(select, authorization, range(AGE, 40.0, null), List.of(), type, null, null));
            assertSameAnswer(new Query(select, authorization, null, List.of(), type, null, null));
            compared += 2;
        }
        assertEquals(clauses.size() * SYNC_TYPES.size() + 2 * SYNC_TYPES.size(), compared);
    }

    @Test
    public void goldenSyncCasesMatchJavabin() throws IOException {
        Assumptions.assumeTrue(Files.exists(GOLDEN), "golden corpus not found at " + GOLDEN.toAbsolutePath());
        JsonNode cases = mapper.readTree(GOLDEN.toFile()).get("cases");
        int compared = 0;
        for (JsonNode goldenCase : cases) {
            if (!"sync".equals(goldenCase.get("kind").asText())) {
                continue;
            }
            Query query = mapper.treeToValue(goldenCase.get("request").get("query"), Query.class);
            assertSameAnswer(query);
            compared++;
        }
        assertFalse(compared == 0, "no sync cases in the golden corpus");
    }

    private void assertSameAnswer(Query query) throws IOException {
        String description = query.expectedResultType() + " " + mapper.writeValueAsString(query.phenotypicClause());
        Object javabin = query.expectedResultType() == ResultType.OBSERVATION_CROSS_COUNT ? javabinObservationCrossCounts(query)
            : answer(javabinCounts, query);
        Object layout = answer(layoutCounts, query);
        if (query.expectedResultType() == ResultType.CROSS_COUNT) {
            assertEquals(javabin, layout, description);
        } else {
            assertEquals(mapper.writeValueAsString(javabin), mapper.writeValueAsString(layout), description);
        }
    }

    /**
     * The javabin OBSERVATION_CROSS_COUNT, one {@code select} path at a time: with several paths its parallel writes into an unsynchronized
     * {@code TreeMap} can drop keys or never return.
     */
    private Map<String, Integer> javabinObservationCrossCounts(Query query) {
        Map<String, Integer> counts = new TreeMap<>();
        for (String path : query.select()) {
            Query single = new Query(
                List.of(path), query.authorizationFilters(), query.phenotypicClause(), query.genomicFilters(), query.expectedResultType(),
                null, null
            );
            counts.putAll(javabinCounts.runObservationCrossCounts(single));
        }
        return counts;
    }

    private static Object answer(PhenotypicCountProcessor processor, Query query) {
        return switch (query.expectedResultType()) {
            case COUNT -> processor.runCounts(query);
            case CROSS_COUNT -> processor.runCrossCounts(query);
            case CATEGORICAL_CROSS_COUNT -> processor.runCategoryCrossCounts(query);
            case CONTINUOUS_CROSS_COUNT -> processor.runContinuousCrossCounts(query);
            case OBSERVATION_CROSS_COUNT -> processor.runObservationCrossCounts(query);
            default -> throw new IllegalArgumentException("not a sync count type: " + query.expectedResultType());
        };
    }

    private static List<String> categories(String path) {
        LayoutConcept concept = layoutStore.index().get(path);
        return concept.categoryValues();
    }

    private static PhenotypicFilter filter(String path, Set<String> values) {
        return new PhenotypicFilter(PhenotypicFilterType.FILTER, path, values, null, null, null);
    }

    private static PhenotypicFilter range(String path, Double min, Double max) {
        return new PhenotypicFilter(PhenotypicFilterType.FILTER, path, null, min, max, null);
    }

    private static PhenotypicFilter required(String path) {
        return new PhenotypicFilter(PhenotypicFilterType.REQUIRED, path, null, null, null, null);
    }

    private static PhenotypicFilter anyRecordOf(String path) {
        return new PhenotypicFilter(PhenotypicFilterType.ANY_RECORD_OF, path, null, null, null, null);
    }

    private static PhenotypicSubquery subquery(Operator operator, PhenotypicClause... clauses) {
        return new PhenotypicSubquery(null, List.of(clauses), operator);
    }
}
