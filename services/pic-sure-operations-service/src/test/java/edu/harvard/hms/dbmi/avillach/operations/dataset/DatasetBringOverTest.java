package edu.harvard.hms.dbmi.avillach.operations.dataset;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import jakarta.persistence.EntityManagerFactory;

import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import edu.harvard.dbmi.avillach.domain.PicSureStatus;
import edu.harvard.dbmi.avillach.domain.QueryRequest;
import edu.harvard.dbmi.avillach.domain.SaveQueryRequest;
import edu.harvard.hms.dbmi.avillach.commons.identity.GatewayUser;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.ResultType;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.Operator;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.PhenotypicFilter;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.PhenotypicFilterType;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.PhenotypicSubquery;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.QueryIdentity;
import edu.harvard.hms.dbmi.avillach.operations.query.InterceptedRepository;
import edu.harvard.hms.dbmi.avillach.operations.query.Query;
import edu.harvard.hms.dbmi.avillach.operations.query.QueryFixtures;
import edu.harvard.hms.dbmi.avillach.operations.query.QueryPersistenceService;
import edu.harvard.hms.dbmi.avillach.operations.query.QueryRepository;

/**
 * Covers the step that brings a caller's datasets over when they are read, through the two reads of {@link NamedDatasetService}, on H2 with
 * the step turned on. No test transaction is open, so every read and every write commits or rolls back as it does in the deployed service,
 * and each test deletes the rows it committed.
 *
 * <p>{@link #BROUGHT_OVER_BODY} is the body the step stores for {@link #STORED_BODY}. {@code QueryServiceTest} in the query service feeds
 * the same text to the status, result and submit paths, so the body asserted here is the body proven readable there. Change the two
 * together.
 */
@SpringBootTest(properties = "picsure.operations.dataset-bring-over.enabled=true")
@ExtendWith(OutputCaptureExtension.class)
@Timeout(120)
class DatasetBringOverTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String ALICE_EMAIL = "alice@example.org";
    private static final UUID ALICE_ID = UUID.fromString("5f0c1f0e-6f0a-4a57-9d6e-0d1c6a3b9f11");
    private static final GatewayUser ALICE = new GatewayUser(ALICE_ID.toString(), "auth0|alice", ALICE_EMAIL, null, Set.of());
    private static final UUID BOB_ID = UUID.fromString("7c1b1f0e-2a3b-4c4d-8e5f-0a1b2c3d4e5f");
    private static final Instant SAVED_LONG_AGO = Instant.parse("2025-03-04T05:06:07Z");
    private static final String CONSENTS = "\\_consents\\";

    /**
     * A request as a stack without query owners stored it: a federated envelope around a v3 query that carries the consent values of the
     * run, a filter on the consent concept path, the id of its old result and a member the v3 query no longer has.
     */
    private static final String STORED_BODY =
        "{\"@type\":\"FederatedQueryRequest\",\"resourceUUID\":\"0e3ad2a1-9b1c-4d53-8f52-2f1d6f0a7c11\","
            + "\"query\":{\"select\":[\"\\\\demographics\\\\AGE\\\\\"],"
            + "\"authorizationFilters\":[{\"conceptPath\":\"\\\\_consents\\\\\",\"values\":[\"phs001.c1\"]}],"
            + "\"phenotypicClause\":{\"operator\":\"AND\",\"phenotypicClauses\":["
            + "{\"phenotypicFilterType\":\"FILTER\",\"conceptPath\":\"\\\\demographics\\\\SEX\\\\\",\"values\":[\"female\"]},"
            + "{\"phenotypicFilterType\":\"FILTER\",\"conceptPath\":\"\\\\_consents\\\\\",\"values\":[\"phs001.c1\"]}]},"
            + "\"genomicFilters\":[],\"expectedResultType\":\"DATAFRAME\",\"picsureId\":\"11111111-2222-4333-8444-555555555555\","
            + "\"memberOfAnOlderRelease\":\"dropped\"},"
            + "\"commonAreaUUID\":\"7a1c0c5e-2f0b-4c7e-9d7a-3b1f2e4d5c6a\",\"resourceCredentials\":{\"token\":\"secret\"}}";

    /** The query of {@link #STORED_BODY} as the query service reads it once the caller submits it: no consent values, no ids. */
    private static final edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.Query STORED_QUERY =
        new edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.Query(
            List.of("\\demographics\\AGE\\"), List.of(),
            new PhenotypicSubquery(
                null,
                List.of(new PhenotypicFilter(PhenotypicFilterType.FILTER, "\\demographics\\SEX\\", Set.of("female"), null, null, null)),
                Operator.AND
            ), List.of(), ResultType.DATAFRAME, null, null
        );

    private static final UUID OWNED_ID = QueryIdentity.idFor(ALICE_ID, STORED_QUERY);
    private static final UUID GENERAL_ID = QueryIdentity.generalIdFor(ALICE_ID, STORED_QUERY);

    /** The body the step stores for {@link #STORED_BODY} and Alice. The same text is in {@code QueryServiceTest} of the query service. */
    private static final String BROUGHT_OVER_BODY =
        "{\"@type\":\"FederatedQueryRequest\",\"resourceUUID\":\"0e3ad2a1-9b1c-4d53-8f52-2f1d6f0a7c11\","
            + "\"query\":{\"select\":[\"\\\\demographics\\\\AGE\\\\\"],\"authorizationFilters\":[],"
            + "\"phenotypicClause\":{\"not\":null,\"phenotypicClauses\":["
            + "{\"phenotypicFilterType\":\"FILTER\",\"conceptPath\":\"\\\\demographics\\\\SEX\\\\\",\"values\":[\"female\"],\"min\":null,\"max\":null,\"not\":null}],"
            + "\"operator\":\"AND\"},\"genomicFilters\":[],\"expectedResultType\":\"DATAFRAME\","
            + "\"picsureId\":\"024abf78-fcdc-54cc-b416-c173cc5bd4ee\",\"id\":null},\"commonAreaUUID\":\"7a1c0c5e-2f0b-4c7e-9d7a-3b1f2e4d5c6a\"}";

    /** A v3 query that names no consent, as text and as the record the step reads it into. */
    private static final String COUNT_QUERY_JSON =
        "\"query\":{\"select\":[\"\\\\demographics\\\\AGE\\\\\"],\"authorizationFilters\":[],\"phenotypicClause\":null,"
            + "\"genomicFilters\":[],\"expectedResultType\":\"COUNT\"}";
    private static final edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.Query COUNT_QUERY =
        new edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.Query(
            List.of("\\demographics\\AGE\\"), List.of(), null, List.of(), ResultType.COUNT, null, null
        );
    private static final String LEFT_AS_IT_IS = " as it is: the query service could not read its stored request";

    @Autowired
    private NamedDatasetService service;

    @Autowired
    private NamedDatasetRepository datasets;

    @Autowired
    private QueryRepository queries;

    @Autowired
    private QueryPersistenceService queryPersistence;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Autowired
    private JdbcTemplate jdbc;

    @AfterEach
    void deleteCommittedRows() {
        jdbc.update("DELETE FROM named_dataset");
        jdbc.update("DELETE FROM query_run");
        jdbc.update("DELETE FROM query");
        jdbc.update("DELETE FROM query_to_variant");
    }

    @Test
    void listBringsOverADatasetOnAnOwnerlessRow(CapturedOutput output) {
        Query ownerless = storedOwnerless(STORED_BODY);
        UUID dataset = datasetOn(ownerless);
        Map<String, Object> ownerlessBefore = rowOf("query", ownerless.getUuid());

        List<NamedDatasetDto> listed = service.listForUser(ALICE);

        assertThat(listed).singleElement().satisfies(answer -> {
            assertThat(answer.uuid()).isEqualTo(dataset);
            assertThat(answer.query().uuid()).isEqualTo(OWNED_ID);
            assertThat(answer.queries()).containsExactly(new LinkedQueryDto(OWNED_ID, false, "DATAFRAME"));
        });
        assertThat(rowOf("named_dataset", dataset)).containsEntry("queryId", hex(OWNED_ID))
            .containsEntry("query_to_variant_uuid", hex(GENERAL_ID));
        assertThat(rowOf("query_to_variant", GENERAL_ID)).containsEntry("user_uuid", hex(ALICE_ID));
        assertThat(rowOf("query", ownerless.getUuid())).isEqualTo(ownerlessBefore).containsEntry("user_uuid", null);
        assertThat(count("query")).isEqualTo(2);
        assertThat(output.getAll())
            .contains("Brought dataset " + dataset + " over from query " + ownerless.getUuid() + " to query " + OWNED_ID)
            .doesNotContain(ALICE_EMAIL).doesNotContain("phs001").doesNotContain("demographics");
    }

    @Test
    void bringingOverInsertsOneQueryRowAndUpdatesNone() {
        datasetOn(storedOwnerless(STORED_BODY));
        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.setStatisticsEnabled(true);
        try {
            statistics.clear();

            service.listForUser(ALICE);

            assertThat(statistics.getEntityStatistics(Query.class.getName()).getInsertCount()).isEqualTo(1);
            assertThat(statistics.getEntityStatistics(Query.class.getName()).getUpdateCount()).isZero();
            assertThat(statistics.getEntityStatistics(NamedDataset.class.getName()).getUpdateCount()).isEqualTo(1);
        } finally {
            statistics.setStatisticsEnabled(false);
        }
    }

    @Test
    void broughtOverRowHasNoConsentsNoStatusNoRunAndTheOldStartTime() {
        Query ownerless = storedOwnerless(STORED_BODY);
        datasetOn(ownerless);

        service.listForUser(ALICE);

        Map<String, Object> owned = rowOf("query", OWNED_ID);
        assertThat(owned).containsEntry("user_uuid", hex(ALICE_ID)).containsEntry("version", "3")
            .containsEntry("resourceResultId", OWNED_ID.toString()).containsEntry("consents", null).containsEntry("status", null)
            .containsEntry("readyTime", null).containsEntry("metadata", null).containsEntry("query_to_variant_uuid", hex(GENERAL_ID))
            .containsEntry("sample", false).containsEntry("result_type", "DATAFRAME");
        Query loaded = queries.findById(OWNED_ID).orElseThrow();
        assertThat(loaded.getStartTime()).isEqualTo(SAVED_LONG_AGO).isEqualTo(ownerless.getStartTime());
        assertThat(loaded.getConsents()).isNull();
        assertThat(loaded.getStatus()).isNull();
        assertThat(count("query_run")).isZero();
    }

    @Test
    void broughtOverBodyIsTheV3QueryWithoutConsentValues() throws Exception {
        datasetOn(storedOwnerless(STORED_BODY));

        service.listForUser(ALICE);

        String body = queries.findById(OWNED_ID).orElseThrow().getQuery();
        assertThat(body).isEqualTo(BROUGHT_OVER_BODY).doesNotContain("phs001").doesNotContain("resourceCredentials")
            .doesNotContain("memberOfAnOlderRelease");
        JsonNode query = MAPPER.readTree(body).get("query");
        assertThat(MAPPER.treeToValue(query, edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.Query.class))
            .isEqualTo(STORED_QUERY.setPicsureId(OWNED_ID));
    }

    /**
     * The query service reads the body of an owned row as a {@link QueryRequest}, which binds {@code resourceUUID} as a UUID, and the body
     * of an owned row is never replaced. Each value here is one the step does not take for a UUID. The last four are ones the query service
     * would read, which the step refuses all the same, because its rule is the canonical text form.
     */
    @ParameterizedTest
    @ValueSource(
        strings = {"\"not-a-uuid\"", "12", "true", "{}", "[\"0e3ad2a1-9b1c-4d53-8f52-2f1d6f0a7c11\"]",
            "\"0e3ad2a19b1c4d538f522f1d6f0a7c11\"", "\"0e3ad2a1-9b1c-4d53-8f52-2f1d6f0a7c1\"", "\"\"", "\"   \"",
            "\" 0e3ad2a1-9b1c-4d53-8f52-2f1d6f0a7c11 \"", "\"AAAAAAAAAAAAAAAAAAAAAA==\""}
    )
    void datasetWhoseStoredRequestHasAResourceUuidThatIsNotAUuidIsLeftAndTheReadAnswers(String resourceUuid, CapturedOutput output) {
        Query ownerless = storedOwnerless("{\"resourceUUID\":" + resourceUuid + "," + COUNT_QUERY_JSON + "}");
        UUID dataset = datasetOn(ownerless);
        Map<String, List<Map<String, Object>>> before = everyRow();

        List<NamedDatasetDto> listed = service.listForUser(ALICE);
        NamedDatasetDto read = service.getForUser(ALICE, dataset);

        assertThat(listed).singleElement().isEqualTo(read).satisfies(answer -> {
            assertThat(answer.uuid()).isEqualTo(dataset);
            assertThat(answer.query().uuid()).isEqualTo(ownerless.getUuid());
            assertThat(answer.queries()).isEmpty();
        });
        assertThat(everyRow()).isEqualTo(before);
        assertThat(output.getAll().lines().filter(line -> line.contains("Left dataset "))).hasSize(2)
            .allSatisfy(line -> assertThat(line).endsWith("Left dataset " + dataset + LEFT_AS_IT_IS));
        assertThat(output.getAll()).doesNotContain(ALICE_EMAIL).doesNotContain("Could not bring dataset")
            .doesNotContain("Brought dataset ");
    }

    @ParameterizedTest
    @ValueSource(
        strings = {"", "\"resourceUUID\":null,", "\"resourceUUID\":\"0e3ad2a1-9b1c-4d53-8f52-2f1d6f0a7c11\",",
            "\"resourceUUID\":\"0E3AD2A1-9B1C-4D53-8F52-2F1D6F0A7C11\","}
    )
    void datasetWhoseStoredRequestHasACanonicalResourceUuidOrNoneIsBroughtOver(String resourceUuidMember, CapturedOutput output)
        throws Exception {
        UUID ownedId = QueryIdentity.idFor(ALICE_ID, COUNT_QUERY);
        Query ownerless = storedOwnerless("{" + resourceUuidMember + COUNT_QUERY_JSON + "}");
        UUID dataset = datasetOn(ownerless);
        JsonNode storedResourceUuid = MAPPER.readTree(ownerless.getQuery()).get("resourceUUID");

        NamedDatasetDto read = service.getForUser(ALICE, dataset);

        assertThat(read.query().uuid()).isEqualTo(ownedId);
        assertThat(read.queries()).containsExactly(new LinkedQueryDto(ownedId, false, "COUNT"));
        String body = queries.findById(ownedId).orElseThrow().getQuery();
        assertThat(MAPPER.readTree(body).get("resourceUUID")).isEqualTo(storedResourceUuid);
        QueryRequest asTheQueryServiceReadsIt = MAPPER.readValue(body, QueryRequest.class);
        UUID expectedResource =
            storedResourceUuid == null || storedResourceUuid.isNull() ? null : UUID.fromString(storedResourceUuid.textValue());
        assertThat(asTheQueryServiceReadsIt.getResourceUUID()).isEqualTo(expectedResource);
        assertThat(output.getAll())
            .contains("Brought dataset " + dataset + " over from query " + ownerless.getUuid() + " to query " + ownedId)
            .doesNotContain("Left dataset ");
    }

    /**
     * Pins which members of a stored request a brought-over body holds: every member but {@code resourceCredentials}, a member no release
     * defines included, with {@code query} replaced by the v3 query under the new row id. The members are copied as they were stored, so
     * the new body holds the stored {@code requesterEmail}. Whether to copy only a fixed list of members is not decided. A change of what
     * is copied must change this test, so that it is made on purpose.
     */
    @Test
    void broughtOverBodyHoldsEveryMemberOfTheStoredRequestButTheCredentials(CapturedOutput output) throws Exception {
        String stored = "{\"@type\":\"GeneralQueryRequest\",\"resourceUUID\":\"0e3ad2a1-9b1c-4d53-8f52-2f1d6f0a7c11\","
            + "\"commonAreaUUID\":\"7a1c0c5e-2f0b-4c7e-9d7a-3b1f2e4d5c6a\",\"requesterEmail\":\"requester@example.org\","
            + "\"institutionOfOrigin\":\"Example University\",\"resourceCredentials\":{\"token\":\"secret\"},"
            + "\"memberNoReleaseDefines\":{\"kept\":[1,true,null]}," + COUNT_QUERY_JSON + "}";
        UUID ownedId = QueryIdentity.idFor(ALICE_ID, COUNT_QUERY);
        datasetOn(storedOwnerless(stored));

        service.listForUser(ALICE);

        String body = queries.findById(ownedId).orElseThrow().getQuery();
        JsonNode broughtOver = MAPPER.readTree(body);
        JsonNode before = MAPPER.readTree(stored);
        List<String> copiedAsStored =
            List.of("@type", "resourceUUID", "commonAreaUUID", "requesterEmail", "institutionOfOrigin", "memberNoReleaseDefines");
        List<String> members = new ArrayList<>();
        broughtOver.fieldNames().forEachRemaining(members::add);
        assertThat(members).containsExactlyInAnyOrder(
            "@type", "resourceUUID", "commonAreaUUID", "requesterEmail", "institutionOfOrigin", "memberNoReleaseDefines", "query"
        );
        assertThat(copiedAsStored).allSatisfy(member -> assertThat(broughtOver.get(member)).isEqualTo(before.get(member)));
        assertThat(MAPPER.treeToValue(broughtOver.get("query"), edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.Query.class))
            .isEqualTo(COUNT_QUERY.setPicsureId(ownedId));
        QueryRequest asTheQueryServiceReadsIt = MAPPER.readValue(body, QueryRequest.class);
        assertThat(asTheQueryServiceReadsIt.getResourceUUID()).isEqualTo(UUID.fromString("0e3ad2a1-9b1c-4d53-8f52-2f1d6f0a7c11"));
        assertThat(MAPPER.convertValue(asTheQueryServiceReadsIt.getQuery(), edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.Query.class))
            .isEqualTo(COUNT_QUERY.setPicsureId(ownedId));
        assertThat(output.getAll()).doesNotContain("requester@example.org").doesNotContain("secret").doesNotContain("Example University");
    }

    @Test
    void storedQueryFromBeforeV3IsTranslatedFirst() throws Exception {
        String beforeV3 = "{\"resourceUUID\":\"0e3ad2a1-9b1c-4d53-8f52-2f1d6f0a7c11\",\"query\":{"
            + "\"categoryFilters\":{\"\\\\demographics\\\\SEX\\\\\":[\"female\"],\"\\\\_consents\\\\\":[\"phs001.c1\"]},"
            + "\"numericFilters\":{\"\\\\demographics\\\\AGE\\\\\":{\"min\":40,\"max\":60}},"
            + "\"fields\":[\"\\\\demographics\\\\AGE\\\\\"],\"expectedResultType\":\"COUNT\"}}";
        Query ownerless = storedOwnerless(beforeV3, null);
        UUID dataset = datasetOn(ownerless);
        edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.Query asTheCallerWouldSubmitIt = queryTheDatasetApiReturnsFor(ownerless);
        UUID expectedId = QueryIdentity.idFor(ALICE_ID, asTheCallerWouldSubmitIt);

        NamedDatasetDto read = service.getForUser(ALICE, dataset);

        assertThat(read.query().uuid()).isEqualTo(expectedId);
        assertThat(read.queries()).containsExactly(new LinkedQueryDto(expectedId, false, "COUNT"));
        Query owned = queries.findById(expectedId).orElseThrow();
        assertThat(owned.getVersion()).isEqualTo("3");
        assertThat(owned.getQueryToVariantUuid()).isEqualTo(QueryIdentity.generalIdFor(ALICE_ID, asTheCallerWouldSubmitIt));
        assertThat(owned.getQuery()).doesNotContain("phs001").doesNotContain("categoryFilters").contains("phenotypicClause");
        JsonNode stored = MAPPER.readTree(owned.getQuery()).get("query");
        assertThat(MAPPER.treeToValue(stored, edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.Query.class))
            .isEqualTo(asTheCallerWouldSubmitIt.setPicsureId(expectedId));
        assertThat(asTheCallerWouldSubmitIt.allFilters()).extracting(PhenotypicFilter::conceptPath)
            .containsExactlyInAnyOrder("\\demographics\\SEX\\", "\\demographics\\AGE\\");
        assertThat(queries.findById(ownerless.getUuid()).orElseThrow().getQuery()).isEqualTo(beforeV3);
    }

    @Test
    void secondListWritesNothing(CapturedOutput output) {
        datasetOn(storedOwnerless(STORED_BODY));
        List<NamedDatasetDto> first = service.listForUser(ALICE);
        Map<String, List<Map<String, Object>>> afterTheFirst = everyRow();

        List<NamedDatasetDto> second = service.listForUser(ALICE);

        assertThat(second).isEqualTo(first);
        assertThat(everyRow()).isEqualTo(afterTheFirst);
        assertThat(output.getAll().split("Brought dataset ", -1)).hasSize(2);
    }

    @Test
    void aSettledCallerCostsNoMoreStatementsThanWithTheStepTurnedOff() {
        datasetOn(storedOwnerless(STORED_BODY));
        service.listForUser(ALICE);
        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.setStatisticsEnabled(true);
        try {
            statistics.clear();
            List<NamedDatasetDto> withTheStepOn = service.listForUser(ALICE);
            long statementsWithTheStepOn = statistics.getPrepareStatementCount();
            statistics.clear();
            List<NamedDatasetDto> withTheStepOff = serviceWith(datasets, false).listForUser(ALICE);

            assertThat(statementsWithTheStepOn).isEqualTo(statistics.getPrepareStatementCount()).isPositive();
            assertThat(withTheStepOn).isEqualTo(withTheStepOff);
        } finally {
            statistics.setStatisticsEnabled(false);
        }
    }

    @Test
    void withTheStepTurnedOffAReadWritesNothing() {
        Query ownerless = storedOwnerless(STORED_BODY);
        UUID dataset = datasetOn(ownerless);
        Map<String, List<Map<String, Object>>> before = everyRow();
        NamedDatasetService turnedOff = serviceWith(datasets, false);

        assertThat(turnedOff.listForUser(ALICE)).singleElement().satisfies(answer -> {
            assertThat(answer.query().uuid()).isEqualTo(ownerless.getUuid());
            assertThat(answer.queries()).isEmpty();
        });
        assertThat(turnedOff.getForUser(ALICE, dataset).query().uuid()).isEqualTo(ownerless.getUuid());

        assertThat(everyRow()).isEqualTo(before);
    }

    /**
     * Before the step existed a read was one read-only transaction: the finder for the caller's datasets, and nothing more for a dataset
     * with no general query. With the step turned off a read of a caller who has a dataset the step would bring over is that and no more:
     * the same number of statements as that plain read, the one finder as the only repository call, and no row written. So it takes no
     * lock, writes nothing and reads nothing a second time.
     */
    @Test
    void withTheStepTurnedOffAReadOfAnUnsettledDatasetCostsWhatAPlainReadCosts() {
        UUID dataset = datasetOn(storedOwnerless(STORED_BODY));
        Map<String, List<Map<String, Object>>> before = everyRow();
        List<String> datasetCalls = new ArrayList<>();
        List<String> queryCalls = new ArrayList<>();
        NamedDatasetRepository recordedDatasets = InterceptedRepository.of(NamedDatasetRepository.class, datasets, (methodName, call) -> {
            datasetCalls.add(methodName);
            return call.proceed();
        });
        QueryRepository recordedQueries = InterceptedRepository.of(QueryRepository.class, queries, (methodName, call) -> {
            queryCalls.add(methodName);
            return call.proceed();
        });
        DatasetBringOver turnedOffStep =
            new DatasetBringOver(recordedDatasets, queryPersistence, transactionManager, new DatasetBringOverProperties(false));
        NamedDatasetService turnedOff =
            new NamedDatasetService(recordedDatasets, recordedQueries, new NamedDatasetMapper(), turnedOffStep, transactionManager);
        NamedDatasetMapper mapper = new NamedDatasetMapper();
        TransactionTemplate readOnly = new TransactionTemplate(transactionManager);
        readOnly.setReadOnly(true);
        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.setStatisticsEnabled(true);
        try {
            long plainList = statementsOf(
                statistics,
                () -> readOnly
                    .execute(status -> datasets.findByUser(ALICE_EMAIL).stream().map(found -> mapper.toDto(found, List.of())).toList())
            );
            long list = statementsOf(statistics, () -> turnedOff.listForUser(ALICE));
            long writesOfTheList = writesCounted(statistics);
            long plainGet = statementsOf(
                statistics,
                () -> readOnly.execute(status -> mapper.toDto(datasets.findByUuidAndUser(dataset, ALICE_EMAIL).orElseThrow(), List.of()))
            );
            long get = statementsOf(statistics, () -> turnedOff.getForUser(ALICE, dataset));
            long writesOfTheGet = writesCounted(statistics);

            assertThat(list).isEqualTo(plainList).isPositive();
            assertThat(get).isEqualTo(plainGet).isPositive();
            assertThat(writesOfTheList).isZero();
            assertThat(writesOfTheGet).isZero();
        } finally {
            statistics.setStatisticsEnabled(false);
        }
        assertThat(datasetCalls).containsExactly("findByUser", "findByUuidAndUser");
        assertThat(queryCalls).isEmpty();
        assertThat(everyRow()).isEqualTo(before);
    }

    @Test
    void getBringsOverTheOneDataset() {
        UUID read = datasetOn(storedOwnerless(STORED_BODY));
        Query other = storedOwnerless("{\"query\":{\"select\":[],\"phenotypicClause\":null,\"expectedResultType\":\"COUNT\"}}");
        UUID notRead = datasetOn(other);

        NamedDatasetDto answer = service.getForUser(ALICE, read);

        assertThat(answer.query().uuid()).isEqualTo(OWNED_ID);
        assertThat(answer.queries()).containsExactly(new LinkedQueryDto(OWNED_ID, false, "DATAFRAME"));
        assertThat(rowOf("named_dataset", notRead)).containsEntry("queryId", hex(other.getUuid()))
            .containsEntry("query_to_variant_uuid", null);
        assertThat(count("query")).isEqualTo(3);
        assertThat(count("query_to_variant")).isEqualTo(1);
    }

    @Test
    void callerWithoutAUuidBringsNothingOver() {
        GatewayUser identifiedBySubject = new GatewayUser("auth0|alice", "auth0|alice", ALICE_EMAIL, null, Set.of());
        Query ownerless = storedOwnerless(STORED_BODY);
        UUID dataset = datasetOn(ownerless);
        Map<String, List<Map<String, Object>>> before = everyRow();

        assertThat(service.listForUser(identifiedBySubject)).singleElement().satisfies(answer -> {
            assertThat(answer.query().uuid()).isEqualTo(ownerless.getUuid());
            assertThat(answer.queries()).isEmpty();
        });
        assertThat(service.getForUser(identifiedBySubject, dataset).query().uuid()).isEqualTo(ownerless.getUuid());

        assertThat(everyRow()).isEqualTo(before);
    }

    @Test
    void datasetOnTheCallersOwnRowTakesItsGeneralQuery(CapturedOutput output) {
        UUID general = QueryFixtures.storedGeneralQuery(jdbc, ALICE_ID);
        Query own = queries.save(QueryFixtures.variant(ALICE_ID, general, false, "DATAFRAME"));
        UUID dataset = datasetOn(own);
        Map<String, Object> ownBefore = rowOf("query", own.getUuid());
        assertThat(rowOf("named_dataset", dataset)).containsEntry("query_to_variant_uuid", null);

        NamedDatasetDto answer = service.getForUser(ALICE, dataset);

        assertThat(answer.query().uuid()).isEqualTo(own.getUuid());
        assertThat(answer.queries()).containsExactly(new LinkedQueryDto(own.getUuid(), false, "DATAFRAME"));
        assertThat(rowOf("named_dataset", dataset)).containsEntry("queryId", hex(own.getUuid()))
            .containsEntry("query_to_variant_uuid", hex(general));
        assertThat(rowOf("query", own.getUuid())).isEqualTo(ownBefore);
        assertThat(count("query")).isEqualTo(1);
        assertThat(count("query_to_variant")).isEqualTo(1);
        assertThat(output.getAll()).contains("Filed dataset " + dataset + " under the general query of its query " + own.getUuid())
            .doesNotContain("Brought dataset ");
    }

    @Test
    void datasetOnAnotherUsersRowIsLeft(CapturedOutput output) {
        UUID bobsGeneral = QueryFixtures.storedGeneralQuery(jdbc, BOB_ID);
        Query bobs = queries.save(QueryFixtures.variant(BOB_ID, bobsGeneral, false, "DATAFRAME"));
        UUID dataset = datasetOn(bobs);
        Map<String, List<Map<String, Object>>> before = everyRow();

        assertThat(service.listForUser(ALICE)).singleElement().satisfies(answer -> {
            assertThat(answer.query().uuid()).isEqualTo(bobs.getUuid());
            assertThat(answer.queries()).isEmpty();
        });
        assertThat(service.getForUser(ALICE, dataset).queries()).isEmpty();

        assertThat(everyRow()).isEqualTo(before);
        assertThat(output.getAll()).doesNotContain("Could not bring dataset");
    }

    @Test
    void anExistingOwnedRowIsReusedAndNotOverwritten() {
        Map<String, List<String>> consents = Map.of(CONSENTS, List.of("phs001.c1"));
        String submittedBody = "{\"query\":{\"select\":[\"\\\\demographics\\\\AGE\\\\\"]},\"submitted\":\"by the caller\"}";
        queryPersistence.save(
            new SaveQueryRequest(
                submittedBody, OWNED_ID.toString(), "AVAILABLE", "3",
                Base64.getEncoder().encodeToString("{}".getBytes(StandardCharsets.UTF_8)), OWNED_ID, ALICE_ID, consents, "FIRST_SUBMIT",
                GENERAL_ID, false, "DATAFRAME"
            )
        );
        Map<String, Object> ownedBefore = rowOf("query", OWNED_ID);
        UUID dataset = datasetOn(storedOwnerless(STORED_BODY));

        NamedDatasetDto answer = service.listForUser(ALICE).getFirst();

        assertThat(answer.query().uuid()).isEqualTo(OWNED_ID);
        assertThat(answer.query().status()).isEqualTo(PicSureStatus.AVAILABLE);
        assertThat(answer.queries()).containsExactly(new LinkedQueryDto(OWNED_ID, false, "DATAFRAME"));
        assertThat(rowOf("query", OWNED_ID)).isEqualTo(ownedBefore);
        assertThat(queries.findById(OWNED_ID).orElseThrow().getQuery()).isEqualTo(submittedBody);
        assertThat(rowOf("named_dataset", dataset)).containsEntry("queryId", hex(OWNED_ID))
            .containsEntry("query_to_variant_uuid", hex(GENERAL_ID));
        assertThat(count("query")).isEqualTo(2);
        assertThat(count("query_run")).isEqualTo(1);
        assertThat(count("query_to_variant")).isEqualTo(1);
    }

    @Test
    void secondDatasetWithTheSameQueryIsLeft(CapturedOutput output) {
        Query one = storedOwnerless(STORED_BODY);
        Query other = storedOwnerless(STORED_BODY);
        datasetOn(one);
        datasetOn(other);

        List<NamedDatasetDto> listed = service.listForUser(ALICE);

        assertThat(listed).extracting(answer -> answer.query().uuid()).hasSize(2).containsOnlyOnce(OWNED_ID)
            .containsAnyOf(one.getUuid(), other.getUuid());
        assertThat(listed).filteredOn(answer -> !answer.query().uuid().equals(OWNED_ID)).singleElement()
            .satisfies(left -> assertThat(left.queries()).isEmpty());
        assertThat(count("query")).isEqualTo(3);
        assertThat(count("query_to_variant")).isEqualTo(1);
        Map<String, List<Map<String, Object>>> afterTheFirst = everyRow();

        assertThat(service.listForUser(ALICE)).isEqualTo(listed);

        assertThat(everyRow()).isEqualTo(afterTheFirst);
        assertThat(output.getAll()).doesNotContain("Could not bring dataset");
        assertThat(output.getAll().split("Brought dataset ", -1)).hasSize(2);
    }

    @Test
    void aFailedWriteLeavesTheDatasetAndTheReadAnswers(CapturedOutput output) {
        Query ownerless = storedOwnerless(STORED_BODY);
        UUID dataset = datasetOn(ownerless);
        Map<String, List<Map<String, Object>>> before = everyRow();
        NamedDatasetRepository refusingTheRepoint = InterceptedRepository.of(NamedDatasetRepository.class, datasets, (methodName, call) -> {
            if ("saveAndFlush".equals(methodName)) {
                throw new DataIntegrityViolationException("refused for " + ALICE_EMAIL);
            }
            return call.proceed();
        });
        NamedDatasetService failing = serviceWith(refusingTheRepoint, true);

        List<NamedDatasetDto> listed = failing.listForUser(ALICE);
        NamedDatasetDto read = failing.getForUser(ALICE, dataset);

        assertThat(listed).singleElement().isEqualTo(read).satisfies(answer -> {
            assertThat(answer.query().uuid()).isEqualTo(ownerless.getUuid());
            assertThat(answer.queries()).isEmpty();
        });
        assertThat(everyRow()).isEqualTo(before);
        assertThat(output.getAll())
            .contains("Could not bring dataset " + dataset + " over: " + DataIntegrityViolationException.class.getName())
            .doesNotContain(ALICE_EMAIL).doesNotContain("Brought dataset ");
    }

    @Test
    void aDatasetThatFailsDoesNotStopTheNext(CapturedOutput output) {
        datasetOn(storedOwnerless(STORED_BODY));
        datasetOn(storedOwnerless("{\"query\":{\"select\":[],\"phenotypicClause\":null,\"expectedResultType\":\"COUNT\"}}"));
        AtomicInteger repoints = new AtomicInteger();
        NamedDatasetRepository refusingTheFirstRepoint =
            InterceptedRepository.of(NamedDatasetRepository.class, datasets, (methodName, call) -> {
                if ("saveAndFlush".equals(methodName) && repoints.incrementAndGet() == 1) {
                    throw new DataIntegrityViolationException("refused");
                }
                return call.proceed();
            });

        List<NamedDatasetDto> listed = serviceWith(refusingTheFirstRepoint, true).listForUser(ALICE);

        assertThat(repoints).hasValue(2);
        assertThat(listed).hasSize(2);
        assertThat(listed).filteredOn(answer -> answer.queries().isEmpty()).hasSize(1);
        assertThat(listed).filteredOn(answer -> !answer.queries().isEmpty()).singleElement()
            .satisfies(broughtOver -> assertThat(broughtOver.queries().getFirst().queryId()).isEqualTo(broughtOver.query().uuid()));
        assertThat(count("query")).isEqualTo(3);
        assertThat(count("query_to_variant")).isEqualTo(1);
        assertThat(output.getAll().split("Could not bring dataset ", -1)).hasSize(2);
        assertThat(output.getAll().split("Brought dataset ", -1)).hasSize(2);
    }

    @Test
    void datasetOnARowWithNoBodyIsLeft(CapturedOutput output) {
        Query noBody = storedOwnerless("");
        datasetOn(noBody);
        Map<String, List<Map<String, Object>>> before = everyRow();

        assertThat(service.listForUser(ALICE)).singleElement().satisfies(answer -> {
            assertThat(answer.query().uuid()).isEqualTo(noBody.getUuid());
            assertThat(answer.queries()).isEmpty();
        });

        assertThat(everyRow()).isEqualTo(before);
        assertThat(output.getAll()).doesNotContain("Could not bring dataset").doesNotContain("Brought dataset ");
    }

    private NamedDatasetService serviceWith(NamedDatasetRepository datasetRepository, boolean stepTurnedOn) {
        DatasetBringOver bringOver =
            new DatasetBringOver(datasetRepository, queryPersistence, transactionManager, new DatasetBringOverProperties(stepTurnedOn));
        return new NamedDatasetService(datasetRepository, queries, new NamedDatasetMapper(), bringOver, transactionManager);
    }

    /** Counts the JDBC statements one piece of work prepares. */
    private static long statementsOf(Statistics statistics, Supplier<?> work) {
        statistics.clear();
        work.get();
        return statistics.getPrepareStatementCount();
    }

    /** Counts the entity rows inserted, updated and deleted since the statistics were last cleared. */
    private static long writesCounted(Statistics statistics) {
        return statistics.getEntityInsertCount() + statistics.getEntityUpdateCount() + statistics.getEntityDeleteCount();
    }

    private Query storedOwnerless(String body) {
        return storedOwnerless(body, "3");
    }

    /** Stores a row as a stack without query owners left it: finished, with the result id HPDS gave it and result metadata. */
    private Query storedOwnerless(String body, String version) {
        Query row = QueryFixtures.withRandomId().setQuery(body).setVersion(version);
        row.setStartTime(SAVED_LONG_AGO);
        row.setReadyTime(SAVED_LONG_AGO.plusSeconds(90));
        row.setStatus(PicSureStatus.AVAILABLE);
        row.setResourceResultId(UUID.randomUUID().toString());
        row.setMetadata("{\"picsureQueryId\":\"old\"}".getBytes(StandardCharsets.UTF_8));
        return queries.save(row);
    }

    /** Stores a dataset of Alice on a query row with no general query, which is how a dataset saved before general queries is stored. */
    private UUID datasetOn(Query row) {
        return datasets.save(new NamedDataset().setUser(ALICE_EMAIL).setName("cohort").setArchived(false).setQuery(row)).getUuid();
    }

    /**
     * Reads the query the dataset API returns for a row, the way the query service reads it when the caller submits it: as a v3 query with
     * a mapper that refuses unknown members, and without authorization filters.
     */
    private static edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.Query queryTheDatasetApiReturnsFor(Query row) throws Exception {
        NamedDataset unsaved = new NamedDataset().setUser(ALICE_EMAIL).setName("cohort").setQuery(row);
        String returned = new NamedDatasetMapper().toDto(unsaved, List.of()).query().query();
        return MAPPER.treeToValue(MAPPER.readTree(returned).get("query"), edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.Query.class)
            .setAuthorizationFilters(List.of());
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    private Map<String, Object> rowOf(String table, UUID id) {
        return comparable(jdbc.queryForMap("SELECT * FROM " + table + " WHERE uuid = ?", bytes(id)));
    }

    /** Reads every row of the four tables the step can write, so that two calls can be compared for any difference. */
    private Map<String, List<Map<String, Object>>> everyRow() {
        Map<String, List<Map<String, Object>>> rows = new LinkedHashMap<>();
        for (String table : List.of("named_dataset", "query", "query_to_variant", "query_run")) {
            String orderedById = "query_run".equals(table) ? "run_id" : "uuid";
            rows.put(
                table,
                jdbc.queryForList("SELECT * FROM " + table + " ORDER BY " + orderedById).stream().map(DatasetBringOverTest::comparable)
                    .toList()
            );
        }
        return rows;
    }

    /** Copies a row with its binary values as hex text, because two byte arrays with the same content are not equal. */
    private static Map<String, Object> comparable(Map<String, Object> row) {
        Map<String, Object> copy = new org.springframework.util.LinkedCaseInsensitiveMap<>();
        row.forEach((column, value) -> copy.put(column, value instanceof byte[] binary ? HexFormat.of().formatHex(binary) : value));
        return copy;
    }

    private static String hex(UUID uuid) {
        return HexFormat.of().formatHex(bytes(uuid));
    }

    private static byte[] bytes(UUID uuid) {
        return ByteBuffer.allocate(16).putLong(uuid.getMostSignificantBits()).putLong(uuid.getLeastSignificantBits()).array();
    }
}
