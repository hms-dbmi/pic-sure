package edu.harvard.hms.dbmi.avillach.operations.dataset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.fasterxml.jackson.databind.ObjectMapper;

import edu.harvard.dbmi.avillach.domain.PicSureStatus;
import edu.harvard.dbmi.avillach.domain.SaveQueryRequest;
import edu.harvard.hms.dbmi.avillach.commons.error.PicsureException;
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
 * Covers the step that files a caller's datasets under the general query of their stored query when they are read, through the two reads of
 * {@link NamedDatasetService}, on H2 with the step turned on. No test transaction is open, so every read and every write commits or rolls
 * back as it does in the deployed service, and each test deletes the rows it committed.
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
    private static final GatewayUser BOB_WITH_ALICES_EMAIL = new GatewayUser(BOB_ID.toString(), "auth0|bob", ALICE_EMAIL, null, Set.of());
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

    /** The ids the query service derives when Alice submits {@link #STORED_QUERY}: of the row it saves and of its general query. */
    private static final UUID OWNED_ID = QueryIdentity.idFor(ALICE_ID, STORED_QUERY);
    private static final UUID GENERAL_ID = QueryIdentity.generalIdFor(ALICE_ID, STORED_QUERY);

    /** A v3 query that names no consent, as a stored body and as the record the step reads it into. */
    private static final String COUNT_BODY =
        "{\"query\":{\"select\":[\"\\\\demographics\\\\AGE\\\\\"],\"authorizationFilters\":[],\"phenotypicClause\":null,"
            + "\"genomicFilters\":[],\"expectedResultType\":\"COUNT\"}}";
    private static final edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.Query COUNT_QUERY =
        new edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.Query(
            List.of("\\demographics\\AGE\\"), List.of(), null, List.of(), ResultType.COUNT, null, null
        );
    private static final UUID COUNT_GENERAL_ID = QueryIdentity.generalIdFor(ALICE_ID, COUNT_QUERY);

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
    void listFilesADatasetOnAnOwnerlessRowUnderTheDerivedGeneralQueryAndKeepsItsQueryId(CapturedOutput output) {
        Query ownerless = storedOwnerless(STORED_BODY);
        UUID dataset = datasetOn(ownerless);
        Map<String, Object> ownerlessBefore = rowOf("query", ownerless.getUuid());

        List<NamedDatasetDto> listed = service.listForUser(ALICE);

        assertThat(listed).singleElement().satisfies(answer -> {
            assertThat(answer.uuid()).isEqualTo(dataset);
            assertThat(answer.query().uuid()).isEqualTo(ownerless.getUuid());
            assertThat(answer.queries()).isEmpty();
        });
        assertThat(rowOf("named_dataset", dataset)).containsEntry("queryId", hex(ownerless.getUuid()))
            .containsEntry("query_to_variant_uuid", hex(GENERAL_ID)).containsEntry("name", "cohort");
        assertThat(rowOf("query_to_variant", GENERAL_ID)).containsEntry("user_uuid", hex(ALICE_ID));
        assertThat(rowOf("query", ownerless.getUuid())).isEqualTo(ownerlessBefore).containsEntry("user_uuid", null)
            .containsEntry("query_to_variant_uuid", null);
        assertThat(count("query")).isEqualTo(1);
        assertThat(count("query_to_variant")).isEqualTo(1);
        assertThat(count("query_run")).isZero();
        assertThat(output.getAll()).contains("Brought dataset " + dataset + " over to general query " + GENERAL_ID)
            .doesNotContain(ALICE_EMAIL).doesNotContain("phs001").doesNotContain("demographics");
    }

    @Test
    void bringingOverInsertsNoQueryRowAndUpdatesNone() {
        datasetOn(storedOwnerless(STORED_BODY));
        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.setStatisticsEnabled(true);
        try {
            statistics.clear();

            service.listForUser(ALICE);

            assertThat(statistics.getEntityStatistics(Query.class.getName()).getInsertCount()).isZero();
            assertThat(statistics.getEntityStatistics(Query.class.getName()).getUpdateCount()).isZero();
            assertThat(statistics.getEntityStatistics(NamedDataset.class.getName()).getUpdateCount()).isEqualTo(1);
        } finally {
            statistics.setStatisticsEnabled(false);
        }
        assertThat(count("query")).isEqualTo(1);
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
        UUID expectedGeneral = QueryIdentity.generalIdFor(ALICE_ID, asTheCallerWouldSubmitIt);

        NamedDatasetDto read = service.getForUser(ALICE, dataset);

        assertThat(read.query().uuid()).isEqualTo(ownerless.getUuid());
        assertThat(rowOf("named_dataset", dataset)).containsEntry("queryId", hex(ownerless.getUuid()))
            .containsEntry("query_to_variant_uuid", hex(expectedGeneral));
        assertThat(asTheCallerWouldSubmitIt.allFilters()).extracting(PhenotypicFilter::conceptPath)
            .containsExactlyInAnyOrder("\\demographics\\SEX\\", "\\demographics\\AGE\\");
        Query unchanged = queries.findById(ownerless.getUuid()).orElseThrow();
        assertThat(unchanged.getQuery()).isEqualTo(beforeV3);
        assertThat(unchanged.getVersion()).isNull();
        assertThat(count("query")).isEqualTo(1);
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
        Query ownerless = storedOwnerless(STORED_BODY);
        UUID read = datasetOn(ownerless);
        Query other = storedOwnerless(COUNT_BODY);
        UUID notRead = datasetOn(other);

        NamedDatasetDto answer = service.getForUser(ALICE, read);

        assertThat(answer.query().uuid()).isEqualTo(ownerless.getUuid());
        assertThat(rowOf("named_dataset", read)).containsEntry("queryId", hex(ownerless.getUuid()))
            .containsEntry("query_to_variant_uuid", hex(GENERAL_ID));
        assertThat(rowOf("named_dataset", notRead)).containsEntry("queryId", hex(other.getUuid()))
            .containsEntry("query_to_variant_uuid", null);
        assertThat(count("query")).isEqualTo(2);
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
    void datasetOnTheCallersOwnRowUnderAnotherGeneralQueryIsCorrected() {
        UUID general = QueryFixtures.storedGeneralQuery(jdbc, ALICE_ID);
        UUID stale = QueryFixtures.storedGeneralQuery(jdbc, ALICE_ID);
        Query own = queries.save(QueryFixtures.variant(ALICE_ID, general, false, "DATAFRAME"));
        UUID dataset = datasetOn(own);
        jdbc.update("UPDATE named_dataset SET query_to_variant_uuid = ? WHERE uuid = ?", bytes(stale), bytes(dataset));

        NamedDatasetDto answer = service.getForUser(ALICE, dataset);

        assertThat(answer.queries()).containsExactly(new LinkedQueryDto(own.getUuid(), false, "DATAFRAME"));
        assertThat(rowOf("named_dataset", dataset)).containsEntry("queryId", hex(own.getUuid()))
            .containsEntry("query_to_variant_uuid", hex(general));
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
    void aGeneralQueryTheCallerAlreadyHasIsReusedAndItsRowsAreListed() {
        saveAsTheQueryServiceDoes(OWNED_ID, false, "DATAFRAME");
        Map<String, Object> ownedBefore = rowOf("query", OWNED_ID);
        Map<String, Object> generalBefore = rowOf("query_to_variant", GENERAL_ID);
        Query ownerless = storedOwnerless(STORED_BODY);
        UUID dataset = datasetOn(ownerless);

        NamedDatasetDto answer = service.listForUser(ALICE).getFirst();

        assertThat(answer.query().uuid()).isEqualTo(ownerless.getUuid());
        assertThat(answer.queries()).containsExactly(new LinkedQueryDto(OWNED_ID, false, "DATAFRAME"));
        assertThat(rowOf("query", OWNED_ID)).isEqualTo(ownedBefore);
        assertThat(rowOf("query_to_variant", GENERAL_ID)).isEqualTo(generalBefore);
        assertThat(rowOf("named_dataset", dataset)).containsEntry("queryId", hex(ownerless.getUuid()))
            .containsEntry("query_to_variant_uuid", hex(GENERAL_ID));
        assertThat(count("query")).isEqualTo(2);
        assertThat(count("query_run")).isEqualTo(1);
        assertThat(count("query_to_variant")).isEqualTo(1);
    }

    @Test
    void aRowSavedUnderTheGeneralQueryAfterTheBringOverIsListedInTheDataset() {
        Query ownerless = storedOwnerless(STORED_BODY);
        UUID dataset = datasetOn(ownerless);
        assertThat(service.getForUser(ALICE, dataset).queries()).isEmpty();
        UUID sampleRow = UUID.randomUUID();

        saveAsTheQueryServiceDoes(OWNED_ID, false, "DATAFRAME");
        saveAsTheQueryServiceDoes(sampleRow, true, "DATAFRAME");

        NamedDatasetDto read = service.getForUser(ALICE, dataset);
        assertThat(read.query().uuid()).isEqualTo(ownerless.getUuid());
        assertThat(read.queries())
            .containsExactly(new LinkedQueryDto(OWNED_ID, false, "DATAFRAME"), new LinkedQueryDto(sampleRow, true, "DATAFRAME"));
        assertThat(service.listForUser(ALICE)).singleElement().isEqualTo(read);
        assertThat(rowOf("named_dataset", dataset)).containsEntry("queryId", hex(ownerless.getUuid()))
            .containsEntry("query_to_variant_uuid", hex(GENERAL_ID));
        assertThat(count("query_to_variant")).isEqualTo(1);
    }

    @Test
    void twoDatasetsThatHoldTheSameQueryGetTheSameGeneralQuery(CapturedOutput output) {
        Query one = storedOwnerless(STORED_BODY);
        Query other = storedOwnerless(STORED_BODY);
        UUID onOne = datasetOn(one);
        UUID onOther = datasetOn(other);

        List<NamedDatasetDto> listed = service.listForUser(ALICE);

        assertThat(listed).extracting(answer -> answer.query().uuid()).containsExactlyInAnyOrder(one.getUuid(), other.getUuid());
        assertThat(rowOf("named_dataset", onOne)).containsEntry("queryId", hex(one.getUuid()))
            .containsEntry("query_to_variant_uuid", hex(GENERAL_ID));
        assertThat(rowOf("named_dataset", onOther)).containsEntry("queryId", hex(other.getUuid()))
            .containsEntry("query_to_variant_uuid", hex(GENERAL_ID));
        assertThat(count("query")).isEqualTo(2);
        assertThat(count("query_to_variant")).isEqualTo(1);
        Map<String, List<Map<String, Object>>> afterTheFirst = everyRow();

        assertThat(service.listForUser(ALICE)).isEqualTo(listed);

        assertThat(everyRow()).isEqualTo(afterTheFirst);
        assertThat(output.getAll()).doesNotContain("Could not bring dataset");
        assertThat(output.getAll().split("Brought dataset ", -1)).hasSize(3);
    }

    @Test
    void aSecondUserWithTheSameEmailDoesNotOverwriteTheGeneralQueryOfTheFirst(CapturedOutput output) {
        Query ownerless = storedOwnerless(STORED_BODY);
        UUID dataset = datasetOn(ownerless);
        List<NamedDatasetDto> asAlice = service.listForUser(ALICE);
        saveAsTheQueryServiceDoes(OWNED_ID, false, "DATAFRAME");
        Map<String, List<Map<String, Object>>> afterAlice = everyRow();

        List<NamedDatasetDto> asBob = service.listForUser(BOB_WITH_ALICES_EMAIL);
        NamedDatasetDto readAsBob = service.getForUser(BOB_WITH_ALICES_EMAIL, dataset);

        assertThat(asBob).singleElement().isEqualTo(readAsBob).isEqualTo(asAlice.getFirst());
        assertThat(readAsBob.queries()).isEmpty();
        assertThat(everyRow()).isEqualTo(afterAlice);
        assertThat(rowOf("named_dataset", dataset)).containsEntry("query_to_variant_uuid", hex(GENERAL_ID));
        assertThat(count("query_to_variant")).isEqualTo(1);
        assertThat(service.getForUser(ALICE, dataset).queries()).containsExactly(new LinkedQueryDto(OWNED_ID, false, "DATAFRAME"));
        assertThat(output.getAll()).doesNotContain("Could not bring dataset");
        assertThat(output.getAll().split("Brought dataset ", -1)).hasSize(2);
    }

    @Test
    void aRenameAndAnArchiveKeepTheGeneralQuery() {
        Query ownerless = storedOwnerless(STORED_BODY);
        UUID dataset = datasetOn(ownerless);
        service.listForUser(ALICE);
        saveAsTheQueryServiceDoes(OWNED_ID, false, "DATAFRAME");

        NamedDatasetDto renamed = service.update(ALICE, dataset, new NamedDatasetRequestDto(ownerless.getUuid(), "renamed", false, null));

        assertThat(renamed.name()).isEqualTo("renamed");
        assertThat(renamed.queries()).containsExactly(new LinkedQueryDto(OWNED_ID, false, "DATAFRAME"));
        assertThat(rowOf("named_dataset", dataset)).containsEntry("queryId", hex(ownerless.getUuid()))
            .containsEntry("query_to_variant_uuid", hex(GENERAL_ID)).containsEntry("name", "renamed");

        NamedDatasetDto archived = service.update(ALICE, dataset, new NamedDatasetRequestDto(ownerless.getUuid(), "renamed", true, null));

        assertThat(archived.archived()).isTrue();
        assertThat(archived.queries()).containsExactly(new LinkedQueryDto(OWNED_ID, false, "DATAFRAME"));
        assertThat(rowOf("named_dataset", dataset)).containsEntry("queryId", hex(ownerless.getUuid()))
            .containsEntry("query_to_variant_uuid", hex(GENERAL_ID));
    }

    @Test
    void aPutOntoAnOwnedRowTakesTheGeneralQueryOfThatRow() {
        UUID dataset = datasetOn(storedOwnerless(STORED_BODY));
        service.listForUser(ALICE);
        UUID general = QueryFixtures.storedGeneralQuery(jdbc, ALICE_ID);
        Query own = queries.save(QueryFixtures.variant(ALICE_ID, general, false, "COUNT"));

        NamedDatasetDto moved = service.update(ALICE, dataset, new NamedDatasetRequestDto(own.getUuid(), "cohort", false, null));

        assertThat(moved.query().uuid()).isEqualTo(own.getUuid());
        assertThat(moved.queries()).containsExactly(new LinkedQueryDto(own.getUuid(), false, "COUNT"));
        assertThat(rowOf("named_dataset", dataset)).containsEntry("queryId", hex(own.getUuid()))
            .containsEntry("query_to_variant_uuid", hex(general));
        Map<String, List<Map<String, Object>>> afterThePut = everyRow();

        assertThat(service.getForUser(ALICE, dataset)).isEqualTo(moved);

        assertThat(everyRow()).isEqualTo(afterThePut);
    }

    @Test
    void aPutOntoAnotherOwnerlessRowClearsTheGeneralQueryAndTheNextReadDerivesTheNewOne() {
        UUID dataset = datasetOn(storedOwnerless(STORED_BODY));
        service.listForUser(ALICE);
        Query other = storedOwnerless(COUNT_BODY);

        NamedDatasetDto moved = service.update(ALICE, dataset, new NamedDatasetRequestDto(other.getUuid(), "cohort", false, null));

        assertThat(moved.query().uuid()).isEqualTo(other.getUuid());
        assertThat(moved.queries()).isEmpty();
        assertThat(rowOf("named_dataset", dataset)).containsEntry("queryId", hex(other.getUuid()))
            .containsEntry("query_to_variant_uuid", null);

        NamedDatasetDto read = service.getForUser(ALICE, dataset);

        assertThat(read.query().uuid()).isEqualTo(other.getUuid());
        assertThat(rowOf("named_dataset", dataset)).containsEntry("queryId", hex(other.getUuid()))
            .containsEntry("query_to_variant_uuid", hex(COUNT_GENERAL_ID));
        assertThat(rowOf("query_to_variant", COUNT_GENERAL_ID)).containsEntry("user_uuid", hex(ALICE_ID));
        assertThat(count("query")).isEqualTo(2);
    }

    /**
     * A stack that does not know the general query column moves {@code queryId} and leaves the column, so the dataset is under the general
     * query of the query it pointed at before. The next read by its user files it under the one of the query it points at now.
     */
    @Test
    void aDatasetWhoseQueryIdWasMovedUnderneathIsFiledAgainOnTheNextRead(CapturedOutput output) {
        UUID dataset = datasetOn(storedOwnerless(STORED_BODY));
        service.listForUser(ALICE);
        Query other = storedOwnerless(COUNT_BODY);
        jdbc.update("UPDATE named_dataset SET queryId = ? WHERE uuid = ?", bytes(other.getUuid()), bytes(dataset));
        assertThat(rowOf("named_dataset", dataset)).containsEntry("query_to_variant_uuid", hex(GENERAL_ID));

        NamedDatasetDto read = service.getForUser(ALICE, dataset);

        assertThat(read.query().uuid()).isEqualTo(other.getUuid());
        assertThat(rowOf("named_dataset", dataset)).containsEntry("queryId", hex(other.getUuid()))
            .containsEntry("query_to_variant_uuid", hex(COUNT_GENERAL_ID));
        assertThat(rowOf("query_to_variant", GENERAL_ID)).containsEntry("user_uuid", hex(ALICE_ID));
        assertThat(count("query_to_variant")).isEqualTo(2);
        assertThat(count("query")).isEqualTo(2);
        assertThat(output.getAll()).contains("Brought dataset " + dataset + " over to general query " + COUNT_GENERAL_ID)
            .doesNotContain("Could not bring dataset");
    }

    @Test
    void aFailedWriteLeavesTheDatasetAndTheReadAnswers(CapturedOutput output) {
        Query ownerless = storedOwnerless(STORED_BODY);
        UUID dataset = datasetOn(ownerless);
        Map<String, List<Map<String, Object>>> before = everyRow();
        NamedDatasetRepository refusingTheWrite = InterceptedRepository.of(NamedDatasetRepository.class, datasets, (methodName, call) -> {
            if ("saveAndFlush".equals(methodName)) {
                throw new DataIntegrityViolationException("refused for " + ALICE_EMAIL);
            }
            return call.proceed();
        });
        NamedDatasetService failing = serviceWith(refusingTheWrite, true);

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
        datasetOn(storedOwnerless(COUNT_BODY));
        AtomicInteger writes = new AtomicInteger();
        NamedDatasetRepository refusingTheFirstWrite =
            InterceptedRepository.of(NamedDatasetRepository.class, datasets, (methodName, call) -> {
                if ("saveAndFlush".equals(methodName) && writes.incrementAndGet() == 1) {
                    throw new DataIntegrityViolationException("refused");
                }
                return call.proceed();
            });

        List<NamedDatasetDto> listed = serviceWith(refusingTheFirstWrite, true).listForUser(ALICE);

        assertThat(writes).hasValue(2);
        assertThat(listed).hasSize(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM named_dataset WHERE query_to_variant_uuid IS NULL", Integer.class))
            .isEqualTo(1);
        assertThat(count("query")).isEqualTo(2);
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

    @Test
    void datasetWhoseStoredBodyCannotBeReadIsLeft(CapturedOutput output) {
        datasetOn(storedOwnerless("not a query"));
        Map<String, List<Map<String, Object>>> before = everyRow();

        assertThatThrownBy(() -> service.listForUser(ALICE)).isInstanceOfSatisfying(
            PicsureException.class, unreadable -> assertThat(unreadable.getStatus()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR)
        );

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

    /**
     * Saves a row of Alice under the general query of {@link #STORED_QUERY}, as the query service does when she submits a variant of it.
     */
    private void saveAsTheQueryServiceDoes(UUID picsureId, boolean sample, String resultType) {
        queryPersistence.save(
            new SaveQueryRequest(
                "{\"query\":{\"select\":[\"\\\\demographics\\\\AGE\\\\\"]}}", picsureId.toString(), "AVAILABLE", "3", null, picsureId,
                ALICE_ID, Map.of(CONSENTS, List.of("phs001.c1")), "FIRST_SUBMIT", GENERAL_ID, sample, resultType
            )
        );
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
