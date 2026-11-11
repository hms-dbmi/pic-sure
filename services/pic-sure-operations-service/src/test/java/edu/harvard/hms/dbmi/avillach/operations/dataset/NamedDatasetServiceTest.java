package edu.harvard.hms.dbmi.avillach.operations.dataset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.AdditionalAnswers.returnsFirstArg;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.PlatformTransactionManager;

import edu.harvard.hms.dbmi.avillach.commons.error.PicsureException;
import edu.harvard.hms.dbmi.avillach.commons.identity.GatewayUser;
import edu.harvard.hms.dbmi.avillach.operations.query.Query;
import edu.harvard.hms.dbmi.avillach.operations.query.QueryRepository;

/**
 * Note: a dedicated {@code PicsureNotFoundException} subclass might be expected here, but none exists in the actual
 * {@code pic-sure-spring-commons} built for this monorepo (it ships only the single public {@link PicsureException}, carrying status via
 * {@code getStatus()}) -- so every not-found case here asserts {@code PicsureException} with {@code HttpStatus.NOT_FOUND}, consistent with
 * how {@code ConfigurationService} already expresses its not-found cases.
 *
 * <p>Cross-user access: {@code findByUuidAndUser} scopes the lookup at the SQL layer, so a caller reading/mutating another user's dataset
 * gets exactly the same {@code PicsureException(NOT_FOUND)} as a genuinely-missing uuid -- there is no separate 403 branch, and existence
 * is never leaked to a non-owning caller.
 *
 * <p>The repositories and the transaction manager are mocks, so these tests pin what the service decides and which rows it asks the
 * repositories to write. What the database does with those rows is pinned by {@code NamedDatasetControllerTest} and
 * {@code NamedDatasetMySqlTest}.
 */
@ExtendWith(MockitoExtension.class)
class NamedDatasetServiceTest {

    private static final String ALICE_EMAIL = "alice@example.com";
    private static final UUID ALICE_ID = UUID.fromString("5f0c1f0e-6f0a-4a57-9d6e-0d1c6a3b9f11");
    private static final UUID BOB_ID = UUID.fromString("7c1b1f0e-2a3b-4c4d-8e5f-0a1b2c3d4e5f");

    /** Gateway-resolved identity for the canonical caller; the service keys datasets on {@code getEmail()}. */
    static final GatewayUser ALICE = new GatewayUser(ALICE_ID.toString(), "auth0|alice", ALICE_EMAIL, null, Set.of());

    private static final String FULL_DATAFRAME =
        "{\"query\":{\"select\":[],\"phenotypicClause\":null,\"expectedResultType\":\"DATAFRAME\"}}";
    private static final String SAMPLE_DATAFRAME =
        "{\"query\":{\"select\":[],\"phenotypicClause\":null,\"expectedResultType\":\"DATAFRAME\",\"sample\":true}}";
    private static final String FULL_PFB = "{\"query\":{\"select\":[],\"phenotypicClause\":null,\"expectedResultType\":\"DATAFRAME_PFB\"}}";
    private static final String NO_RESULT_TYPE = "{\"query\":{\"select\":[],\"phenotypicClause\":null}}";

    @Mock
    private NamedDatasetRepository repo;

    @Mock
    private NamedDatasetQueryRepository links;

    @Mock
    private QueryRepository queryRepo;

    @Mock
    private PlatformTransactionManager transactionManager;

    private NamedDatasetService service;

    @BeforeEach
    void buildService() {
        service = new NamedDatasetService(repo, links, queryRepo, new NamedDatasetMapper(), transactionManager);
    }

    private static Query storedQuery(UUID owner, String body) {
        Query query = new Query();
        query.setUuid(UUID.randomUUID());
        query.setQuery(body).setVersion("3");
        query.setUserUuid(owner);
        return query;
    }

    private static NamedDataset datasetPointingAt(Query query) {
        NamedDataset dataset = new NamedDataset().setUser(ALICE_EMAIL).setName("d1").setQuery(query).setArchived(false);
        dataset.setUuid(UUID.randomUUID());
        return dataset;
    }

    private static NamedDatasetQuery joinRow(NamedDataset dataset, Query query, boolean sample, String resultType) {
        return new NamedDatasetQuery(dataset.getUuid(), query.getUuid(), sample, resultType, Instant.parse("2026-01-02T03:04:05.678Z"));
    }

    private static NamedDatasetRequestDto linkRequest(Query query) {
        return new NamedDatasetRequestDto(query.getUuid(), "d1", false, null);
    }

    private void callerOwns(NamedDataset dataset, NamedDatasetQuery... rows) {
        when(repo.findByUuidAndUser(dataset.getUuid(), ALICE_EMAIL)).thenReturn(Optional.of(dataset));
        when(links.findByDatasets(List.of(dataset.getUuid()))).thenReturn(List.of(rows));
    }

    private void queryIsStored(Query query) {
        when(queryRepo.findById(query.getUuid())).thenReturn(Optional.of(query));
    }

    private NamedDatasetQuery insertedJoinRow() {
        ArgumentCaptor<NamedDatasetQuery> inserted = ArgumentCaptor.forClass(NamedDatasetQuery.class);
        verify(links).saveAndFlush(inserted.capture());
        return inserted.getValue();
    }

    private void assertNothingWasLinked() {
        verify(links, never()).saveAndFlush(any());
        verify(repo, never()).saveAndFlush(any());
    }

    @Test
    void listsByUserEmail() {
        Query q = new Query();
        q.setUuid(UUID.randomUUID());
        NamedDataset e = new NamedDataset().setUser(ALICE_EMAIL).setName("d1").setQuery(q);
        e.setUuid(UUID.randomUUID());
        when(repo.findByUser(ALICE_EMAIL)).thenReturn(List.of(e));

        List<NamedDatasetDto> result = service.listForUser(ALICE);

        assertThat(result).hasSize(1).extracting(NamedDatasetDto::name).containsExactly("d1");
    }

    @Test
    void listReturnsTheQueriesOfEachDataset() {
        Query first = storedQuery(ALICE_ID, FULL_DATAFRAME);
        Query firstSample = storedQuery(ALICE_ID, SAMPLE_DATAFRAME);
        Query second = storedQuery(ALICE_ID, FULL_PFB);
        NamedDataset one = datasetPointingAt(first);
        NamedDataset two = datasetPointingAt(second);
        when(repo.findByUser(ALICE_EMAIL)).thenReturn(List.of(one, two));
        when(links.findByDatasets(List.of(one.getUuid(), two.getUuid()))).thenReturn(
            List.of(
                joinRow(one, first, false, "DATAFRAME"), joinRow(two, second, false, "DATAFRAME_PFB"),
                joinRow(one, firstSample, true, "DATAFRAME")
            )
        );

        List<NamedDatasetDto> result = service.listForUser(ALICE);

        assertThat(result.get(0).queries()).containsExactly(
            new LinkedQueryDto(first.getUuid(), false, "DATAFRAME"), new LinkedQueryDto(firstSample.getUuid(), true, "DATAFRAME")
        );
        assertThat(result.get(1).queries()).containsExactly(new LinkedQueryDto(second.getUuid(), false, "DATAFRAME_PFB"));
    }

    @Test
    void findByIdScopedToUserThrows404WhenAbsentOrNotOwned() {
        UUID id = UUID.randomUUID();
        when(repo.findByUuidAndUser(id, ALICE_EMAIL)).thenReturn(Optional.empty());

        PicsureException ex = assertThrows(PicsureException.class, () -> service.getForUser(ALICE, id));

        assertThat(ex.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void getForUserReturnsDtoWhenOwned() {
        UUID id = UUID.randomUUID();
        NamedDataset e = new NamedDataset().setUser(ALICE_EMAIL).setName("d1");
        e.setUuid(id);
        when(repo.findByUuidAndUser(id, ALICE_EMAIL)).thenReturn(Optional.of(e));

        NamedDatasetDto dto = service.getForUser(ALICE, id);

        assertThat(dto.name()).isEqualTo("d1");
        assertThat(dto.queries()).isEmpty();
    }

    @Test
    void readIncludesTheQueryIdRowWhenItHasNoJoinRow() {
        Query full = storedQuery(ALICE_ID, FULL_DATAFRAME);
        NamedDataset dataset = datasetPointingAt(full);
        callerOwns(dataset);

        NamedDatasetDto dto = service.getForUser(ALICE, dataset.getUuid());

        assertThat(dto.queries()).containsExactly(new LinkedQueryDto(full.getUuid(), false, "DATAFRAME"));
        verify(links, never()).fillResultType(any(), any(), any());
    }

    @Test
    void readListsTheQueryIdRowOnceWhenItHasAJoinRow() {
        Query full = storedQuery(ALICE_ID, FULL_DATAFRAME);
        Query sample = storedQuery(ALICE_ID, SAMPLE_DATAFRAME);
        NamedDataset dataset = datasetPointingAt(full);
        callerOwns(dataset, joinRow(dataset, full, false, "DATAFRAME"), joinRow(dataset, sample, true, "DATAFRAME"));

        NamedDatasetDto dto = service.getForUser(ALICE, dataset.getUuid());

        assertThat(dto.query().uuid()).isEqualTo(full.getUuid());
        assertThat(dto.queries()).containsExactly(
            new LinkedQueryDto(full.getUuid(), false, "DATAFRAME"), new LinkedQueryDto(sample.getUuid(), true, "DATAFRAME")
        );
        verify(links, never()).fillResultType(any(), any(), any());
    }

    @Test
    void backfilledJoinRowWithNullResultTypeReadsItFromTheBody() {
        Query full = storedQuery(null, FULL_DATAFRAME);
        NamedDataset dataset = datasetPointingAt(full);
        callerOwns(dataset, joinRow(dataset, full, false, null));

        NamedDatasetDto dto = service.getForUser(ALICE, dataset.getUuid());

        assertThat(dto.queries()).containsExactly(new LinkedQueryDto(full.getUuid(), false, "DATAFRAME"));
        verify(links).fillResultType(dataset.getUuid(), full.getUuid(), "DATAFRAME");
    }

    @Test
    void joinRowWithNullResultTypeForAQueryOtherThanTheQueryIdRowReadsThatQuerysBody() {
        Query full = storedQuery(ALICE_ID, FULL_DATAFRAME);
        Query other = storedQuery(ALICE_ID, FULL_PFB);
        NamedDataset dataset = datasetPointingAt(full);
        callerOwns(dataset, joinRow(dataset, full, false, "DATAFRAME"), joinRow(dataset, other, false, null));
        when(queryRepo.findAllById(Set.of(other.getUuid()))).thenReturn(List.of(other));

        NamedDatasetDto dto = service.getForUser(ALICE, dataset.getUuid());

        assertThat(dto.queries()).containsExactly(
            new LinkedQueryDto(full.getUuid(), false, "DATAFRAME"), new LinkedQueryDto(other.getUuid(), false, "DATAFRAME_PFB")
        );
        verify(links).fillResultType(dataset.getUuid(), other.getUuid(), "DATAFRAME_PFB");
    }

    @Test
    void joinRowWhoseBodyNamesNoResultTypeIsReturnedWithoutOneAndNothingIsWrittenBack() {
        Query untyped = storedQuery(null, NO_RESULT_TYPE);
        NamedDataset dataset = datasetPointingAt(untyped);
        callerOwns(dataset, joinRow(dataset, untyped, false, null));

        NamedDatasetDto dto = service.getForUser(ALICE, dataset.getUuid());

        assertThat(dto.queries()).containsExactly(new LinkedQueryDto(untyped.getUuid(), false, null));
        verify(links, never()).fillResultType(any(), any(), any());
    }

    @Test
    void writeBackOfADerivedResultTypeSurvivesAnExistingTypedRowForTheSameVariant() {
        Query backfilled = storedQuery(null, FULL_DATAFRAME);
        Query typed = storedQuery(ALICE_ID, FULL_DATAFRAME);
        NamedDataset dataset = datasetPointingAt(backfilled);
        callerOwns(dataset, joinRow(dataset, backfilled, false, null), joinRow(dataset, typed, false, "DATAFRAME"));
        when(links.fillResultType(dataset.getUuid(), backfilled.getUuid(), "DATAFRAME"))
            .thenThrow(new DataIntegrityViolationException("unique_named_dataset_variant"));

        NamedDatasetDto dto = service.getForUser(ALICE, dataset.getUuid());

        assertThat(dto.queries()).containsExactly(
            new LinkedQueryDto(backfilled.getUuid(), false, "DATAFRAME"), new LinkedQueryDto(typed.getUuid(), false, "DATAFRAME")
        );
    }

    @Test
    void createResolvesQueryAndReturnsDto() {
        UUID queryId = UUID.randomUUID();
        Query q = new Query();
        q.setUuid(queryId);
        when(queryRepo.findById(queryId)).thenReturn(Optional.of(q));
        when(repo.saveAndFlush(any())).thenAnswer(inv -> {
            NamedDataset e = inv.getArgument(0);
            e.setUuid(UUID.randomUUID());
            return e;
        });

        NamedDatasetRequestDto req = new NamedDatasetRequestDto(queryId, "d2", false, null);
        NamedDatasetDto dto = service.create(ALICE, req);

        assertThat(dto.name()).isEqualTo("d2");
        assertThat(dto.query().uuid()).isEqualTo(queryId);
    }

    @Test
    void createAddsAJoinRow() {
        Query pfb = storedQuery(ALICE_ID, FULL_PFB);
        queryIsStored(pfb);
        when(repo.saveAndFlush(any())).thenAnswer(inv -> {
            NamedDataset e = inv.getArgument(0);
            e.setUuid(UUID.randomUUID());
            return e;
        });

        NamedDatasetDto dto = service.create(ALICE, linkRequest(pfb));

        NamedDatasetQuery row = insertedJoinRow();
        assertThat(row.getNamedDatasetUuid()).isEqualTo(dto.uuid());
        assertThat(row.getQueryUuid()).isEqualTo(pfb.getUuid());
        assertThat(row.isSample()).isFalse();
        assertThat(row.getResultType()).isEqualTo("DATAFRAME_PFB");
        assertThat(row.getCreatedAt()).isNotNull();
        assertThat(dto.query().uuid()).isEqualTo(pfb.getUuid());
        assertThat(dto.queries()).containsExactly(new LinkedQueryDto(pfb.getUuid(), false, "DATAFRAME_PFB"));
    }

    @Test
    void createReadsTheSampleFlagOfTheJoinRowFromTheQueryBody() {
        Query sample = storedQuery(ALICE_ID, SAMPLE_DATAFRAME);
        queryIsStored(sample);
        when(repo.saveAndFlush(any())).thenAnswer(inv -> {
            NamedDataset e = inv.getArgument(0);
            e.setUuid(UUID.randomUUID());
            return e;
        });

        service.create(ALICE, linkRequest(sample));

        NamedDatasetQuery row = insertedJoinRow();
        assertThat(row.isSample()).isTrue();
        assertThat(row.getResultType()).isEqualTo("DATAFRAME");
    }

    @Test
    void creatingADatasetOnAnotherUsersQueryIs403AndWritesNothing() {
        Query bobs = storedQuery(BOB_ID, FULL_DATAFRAME);
        queryIsStored(bobs);

        PicsureException ex = assertThrows(PicsureException.class, () -> service.create(ALICE, linkRequest(bobs)));

        assertThat(ex.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(ex.getErrorType()).isEqualTo("forbidden");
        assertNothingWasLinked();
    }

    @Test
    void creatingADatasetOnAnOwnedQueryIs403ForACallerWhoseGatewayUserIdIsNotAUuid() {
        GatewayUser subjectOnly = new GatewayUser("auth0|alice", "auth0|alice", ALICE_EMAIL, null, Set.of());
        Query alices = storedQuery(ALICE_ID, FULL_DATAFRAME);
        queryIsStored(alices);

        PicsureException ex = assertThrows(PicsureException.class, () -> service.create(subjectOnly, linkRequest(alices)));

        assertThat(ex.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
        assertNothingWasLinked();
    }

    @Test
    void creatingADatasetOnAnOwnerlessQueryIsAllowed() {
        Query ownerless = storedQuery(null, FULL_DATAFRAME);
        queryIsStored(ownerless);
        when(repo.saveAndFlush(any())).thenAnswer(inv -> {
            NamedDataset e = inv.getArgument(0);
            e.setUuid(UUID.randomUUID());
            return e;
        });

        NamedDatasetDto dto = service.create(ALICE, linkRequest(ownerless));

        assertThat(dto.query().uuid()).isEqualTo(ownerless.getUuid());
        assertThat(insertedJoinRow().getQueryUuid()).isEqualTo(ownerless.getUuid());
    }

    @Test
    void creatingADatasetOnTheCallersOwnQueryIsAllowed() {
        Query alices = storedQuery(ALICE_ID, FULL_DATAFRAME);
        queryIsStored(alices);
        when(repo.saveAndFlush(any())).thenAnswer(inv -> {
            NamedDataset e = inv.getArgument(0);
            e.setUuid(UUID.randomUUID());
            return e;
        });

        NamedDatasetDto dto = service.create(ALICE, linkRequest(alices));

        assertThat(dto.query().uuid()).isEqualTo(alices.getUuid());
        assertThat(insertedJoinRow().getQueryUuid()).isEqualTo(alices.getUuid());
    }

    @Test
    void createDuplicateQueryAndUserThrows409() {
        UUID queryId = UUID.randomUUID();
        Query q = new Query();
        q.setUuid(queryId);
        when(queryRepo.findById(queryId)).thenReturn(Optional.of(q));
        when(repo.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("unique_queryId_user"));

        NamedDatasetRequestDto req = new NamedDatasetRequestDto(queryId, "dup", false, null);
        PicsureException ex = assertThrows(PicsureException.class, () -> service.create(ALICE, req));

        assertThat(ex.getStatus()).isEqualTo(HttpStatus.CONFLICT);
        verify(links, never()).saveAndFlush(any());
    }

    @Test
    void createWithMissingQueryThrows404() {
        UUID queryId = UUID.randomUUID();
        when(queryRepo.findById(queryId)).thenReturn(Optional.empty());
        NamedDatasetRequestDto req = new NamedDatasetRequestDto(queryId, "d3", false, null);

        PicsureException ex = assertThrows(PicsureException.class, () -> service.create(ALICE, req));

        assertThat(ex.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void updateScopedToUserThrows404WhenNotOwned() {
        UUID id = UUID.randomUUID();
        when(repo.findByUuidAndUser(id, ALICE_EMAIL)).thenReturn(Optional.empty());
        NamedDatasetRequestDto req = new NamedDatasetRequestDto(UUID.randomUUID(), "renamed", false, null);

        PicsureException ex = assertThrows(PicsureException.class, () -> service.update(ALICE, id, req));

        assertThat(ex.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void updateAppliesNameArchivedAndMetadata() {
        UUID id = UUID.randomUUID();
        UUID queryId = UUID.randomUUID();
        Query q = new Query();
        q.setUuid(queryId);
        NamedDataset existing = new NamedDataset().setUser(ALICE_EMAIL).setName("old").setQuery(q).setArchived(false);
        existing.setUuid(id);
        when(repo.findByUuidAndUser(id, ALICE_EMAIL)).thenReturn(Optional.of(existing));
        when(repo.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

        NamedDatasetRequestDto req = new NamedDatasetRequestDto(queryId, "renamed", true, null);
        NamedDatasetDto dto = service.update(ALICE, id, req);

        assertThat(dto.name()).isEqualTo("renamed");
        assertThat(dto.archived()).isTrue();
        verify(links, never()).saveAndFlush(any());
        verifyNoInteractions(queryRepo);
    }

    @Test
    void putWithAQueryThatAlreadyHasAJoinRowAddsNoSecondOne() {
        Query full = storedQuery(ALICE_ID, FULL_DATAFRAME);
        Query sample = storedQuery(BOB_ID, SAMPLE_DATAFRAME);
        NamedDataset dataset = datasetPointingAt(full);
        callerOwns(dataset, joinRow(dataset, full, false, "DATAFRAME"), joinRow(dataset, sample, true, "DATAFRAME"));
        when(repo.saveAndFlush(any())).thenAnswer(returnsFirstArg());

        NamedDatasetDto dto =
            service.update(ALICE, dataset.getUuid(), new NamedDatasetRequestDto(sample.getUuid(), "renamed", false, null));

        assertThat(dto.name()).isEqualTo("renamed");
        assertThat(dto.query().uuid()).isEqualTo(full.getUuid());
        verify(links, never()).saveAndFlush(any());
        verifyNoInteractions(queryRepo);
    }

    @Test
    void putWithANewSampleQueryLinksItAndKeepsQueryIdOnTheFullRow() {
        Query full = storedQuery(ALICE_ID, FULL_DATAFRAME);
        Query sample = storedQuery(ALICE_ID, SAMPLE_DATAFRAME);
        NamedDataset dataset = datasetPointingAt(full);
        callerOwns(dataset, joinRow(dataset, full, false, "DATAFRAME"));
        queryIsStored(sample);
        when(repo.saveAndFlush(any())).thenAnswer(returnsFirstArg());

        NamedDatasetDto dto = service.update(ALICE, dataset.getUuid(), linkRequest(sample));

        NamedDatasetQuery row = insertedJoinRow();
        assertThat(row.getNamedDatasetUuid()).isEqualTo(dataset.getUuid());
        assertThat(row.getQueryUuid()).isEqualTo(sample.getUuid());
        assertThat(row.isSample()).isTrue();
        assertThat(row.getResultType()).isEqualTo("DATAFRAME");
        assertThat(dataset.getQuery()).isSameAs(full);
        assertThat(dto.query().uuid()).isEqualTo(full.getUuid());
        assertThat(dto.queries()).containsExactly(
            new LinkedQueryDto(full.getUuid(), false, "DATAFRAME"), new LinkedQueryDto(sample.getUuid(), true, "DATAFRAME")
        );
    }

    @Test
    void putWithANewFullQueryOfAnotherResultTypeLinksItAndKeepsQueryIdWhereItWas() {
        Query full = storedQuery(ALICE_ID, FULL_DATAFRAME);
        Query pfb = storedQuery(ALICE_ID, FULL_PFB);
        NamedDataset dataset = datasetPointingAt(full);
        callerOwns(dataset, joinRow(dataset, full, false, "DATAFRAME"));
        queryIsStored(pfb);
        when(repo.saveAndFlush(any())).thenAnswer(returnsFirstArg());

        NamedDatasetDto dto = service.update(ALICE, dataset.getUuid(), linkRequest(pfb));

        assertThat(insertedJoinRow().getQueryUuid()).isEqualTo(pfb.getUuid());
        assertThat(dataset.getQuery()).isSameAs(full);
        assertThat(dto.query().uuid()).isEqualTo(full.getUuid());
    }

    @Test
    void putWithANewFullQueryMovesQueryIdWhenThePointerIsNull() {
        Query full = storedQuery(ALICE_ID, FULL_DATAFRAME);
        NamedDataset dataset = datasetPointingAt(null);
        callerOwns(dataset);
        queryIsStored(full);
        when(repo.saveAndFlush(any())).thenAnswer(returnsFirstArg());

        NamedDatasetDto dto = service.update(ALICE, dataset.getUuid(), linkRequest(full));

        assertThat(dataset.getQuery()).isSameAs(full);
        assertThat(dto.query().uuid()).isEqualTo(full.getUuid());
        assertThat(insertedJoinRow().getQueryUuid()).isEqualTo(full.getUuid());
    }

    @Test
    void putWithANewSampleQueryLeavesANullQueryIdNull() {
        Query sample = storedQuery(ALICE_ID, SAMPLE_DATAFRAME);
        NamedDataset dataset = datasetPointingAt(null);
        callerOwns(dataset);
        queryIsStored(sample);
        when(repo.saveAndFlush(any())).thenAnswer(returnsFirstArg());

        NamedDatasetDto dto = service.update(ALICE, dataset.getUuid(), linkRequest(sample));

        assertThat(dataset.getQuery()).isNull();
        assertThat(dto.query()).isNull();
        assertThat(insertedJoinRow().getQueryUuid()).isEqualTo(sample.getUuid());
    }

    @Test
    void putMovingANullQueryIdOntoAQueryTheCallerAlreadyNamedIs409() {
        Query full = storedQuery(ALICE_ID, FULL_DATAFRAME);
        NamedDataset dataset = datasetPointingAt(null);
        callerOwns(dataset);
        queryIsStored(full);
        when(repo.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("unique_queryId_user"));

        PicsureException ex = assertThrows(PicsureException.class, () -> service.update(ALICE, dataset.getUuid(), linkRequest(full)));

        assertThat(ex.getStatus()).isEqualTo(HttpStatus.CONFLICT);
        verify(links, never()).saveAndFlush(any());
    }

    @Test
    void linkingAnotherUsersQueryIs403() {
        Query full = storedQuery(ALICE_ID, FULL_DATAFRAME);
        Query bobsSample = storedQuery(BOB_ID, SAMPLE_DATAFRAME);
        NamedDataset dataset = datasetPointingAt(full);
        callerOwns(dataset, joinRow(dataset, full, false, "DATAFRAME"));
        queryIsStored(bobsSample);

        PicsureException ex = assertThrows(PicsureException.class, () -> service.update(ALICE, dataset.getUuid(), linkRequest(bobsSample)));

        assertThat(ex.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
        assertNothingWasLinked();
    }

    @Test
    void linkingAnOwnedQueryIs403ForACallerWhoseGatewayUserIdIsNotAUuid() {
        GatewayUser subjectOnly = new GatewayUser("auth0|alice", "auth0|alice", ALICE_EMAIL, null, Set.of());
        Query full = storedQuery(ALICE_ID, FULL_DATAFRAME);
        Query sample = storedQuery(ALICE_ID, SAMPLE_DATAFRAME);
        NamedDataset dataset = datasetPointingAt(full);
        callerOwns(dataset, joinRow(dataset, full, false, "DATAFRAME"));
        queryIsStored(sample);

        PicsureException ex =
            assertThrows(PicsureException.class, () -> service.update(subjectOnly, dataset.getUuid(), linkRequest(sample)));

        assertThat(ex.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
        assertNothingWasLinked();
    }

    @Test
    void linkingTheCallersOwnQueryIgnoresTheLetterCaseOfTheGatewayUserId() {
        GatewayUser upperCase = new GatewayUser(ALICE_ID.toString().toUpperCase(), "auth0|alice", ALICE_EMAIL, null, Set.of());
        Query full = storedQuery(ALICE_ID, FULL_DATAFRAME);
        Query sample = storedQuery(ALICE_ID, SAMPLE_DATAFRAME);
        NamedDataset dataset = datasetPointingAt(full);
        callerOwns(dataset, joinRow(dataset, full, false, "DATAFRAME"));
        queryIsStored(sample);
        when(repo.saveAndFlush(any())).thenAnswer(returnsFirstArg());

        service.update(upperCase, dataset.getUuid(), linkRequest(sample));

        assertThat(insertedJoinRow().getQueryUuid()).isEqualTo(sample.getUuid());
    }

    @Test
    void linkingAnOwnerlessQueryIsAllowed() {
        Query full = storedQuery(ALICE_ID, FULL_DATAFRAME);
        Query ownerlessSample = storedQuery(null, SAMPLE_DATAFRAME);
        NamedDataset dataset = datasetPointingAt(full);
        callerOwns(dataset, joinRow(dataset, full, false, "DATAFRAME"));
        queryIsStored(ownerlessSample);
        when(repo.saveAndFlush(any())).thenAnswer(returnsFirstArg());

        NamedDatasetDto dto = service.update(ALICE, dataset.getUuid(), linkRequest(ownerlessSample));

        assertThat(insertedJoinRow().getQueryUuid()).isEqualTo(ownerlessSample.getUuid());
        assertThat(dto.queries()).contains(new LinkedQueryDto(ownerlessSample.getUuid(), true, "DATAFRAME"));
    }

    @Test
    void secondRowForTheSameVariantIs409() {
        Query full = storedQuery(ALICE_ID, FULL_DATAFRAME);
        Query sample = storedQuery(ALICE_ID, SAMPLE_DATAFRAME);
        Query secondSample = storedQuery(ALICE_ID, SAMPLE_DATAFRAME);
        NamedDataset dataset = datasetPointingAt(full);
        callerOwns(dataset, joinRow(dataset, full, false, "DATAFRAME"), joinRow(dataset, sample, true, "DATAFRAME"));
        queryIsStored(secondSample);

        PicsureException ex =
            assertThrows(PicsureException.class, () -> service.update(ALICE, dataset.getUuid(), linkRequest(secondSample)));

        assertThat(ex.getStatus()).isEqualTo(HttpStatus.CONFLICT);
        assertNothingWasLinked();
    }

    @Test
    void secondRowForTheVariantOfTheQueryIdRowIs409WhenThatRowHasNoJoinRow() {
        Query full = storedQuery(ALICE_ID, FULL_DATAFRAME);
        Query secondFull = storedQuery(ALICE_ID, FULL_DATAFRAME);
        NamedDataset dataset = datasetPointingAt(full);
        callerOwns(dataset);
        queryIsStored(secondFull);

        PicsureException ex = assertThrows(PicsureException.class, () -> service.update(ALICE, dataset.getUuid(), linkRequest(secondFull)));

        assertThat(ex.getStatus()).isEqualTo(HttpStatus.CONFLICT);
        assertNothingWasLinked();
    }

    @Test
    void secondRowForTheVariantOfABackfilledRowIs409() {
        Query backfilled = storedQuery(null, FULL_DATAFRAME);
        Query secondFull = storedQuery(ALICE_ID, FULL_DATAFRAME);
        NamedDataset dataset = datasetPointingAt(backfilled);
        callerOwns(dataset, joinRow(dataset, backfilled, false, null));
        queryIsStored(secondFull);

        PicsureException ex = assertThrows(PicsureException.class, () -> service.update(ALICE, dataset.getUuid(), linkRequest(secondFull)));

        assertThat(ex.getStatus()).isEqualTo(HttpStatus.CONFLICT);
        assertNothingWasLinked();
    }

    @Test
    void joinRowThatLosesARaceOnTheVariantKeyIs409() {
        Query full = storedQuery(ALICE_ID, FULL_DATAFRAME);
        Query sample = storedQuery(ALICE_ID, SAMPLE_DATAFRAME);
        NamedDataset dataset = datasetPointingAt(full);
        callerOwns(dataset, joinRow(dataset, full, false, "DATAFRAME"));
        queryIsStored(sample);
        when(repo.saveAndFlush(any())).thenAnswer(returnsFirstArg());
        when(links.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("unique_named_dataset_variant"));

        PicsureException ex = assertThrows(PicsureException.class, () -> service.update(ALICE, dataset.getUuid(), linkRequest(sample)));

        assertThat(ex.getStatus()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void putWithAQueryThatIsNotStoredIs404() {
        Query full = storedQuery(ALICE_ID, FULL_DATAFRAME);
        NamedDataset dataset = datasetPointingAt(full);
        callerOwns(dataset, joinRow(dataset, full, false, "DATAFRAME"));
        UUID unknown = UUID.randomUUID();
        when(queryRepo.findById(unknown)).thenReturn(Optional.empty());

        PicsureException ex = assertThrows(
            PicsureException.class, () -> service.update(ALICE, dataset.getUuid(), new NamedDatasetRequestDto(unknown, "d1", false, null))
        );

        assertThat(ex.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
        assertNothingWasLinked();
    }

    @Test
    void deleteScopedToUserThrows404WhenNotOwned() {
        UUID id = UUID.randomUUID();
        when(repo.findByUuidAndUser(id, ALICE_EMAIL)).thenReturn(Optional.empty());

        PicsureException ex = assertThrows(PicsureException.class, () -> service.delete(ALICE, id));

        assertThat(ex.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void deleteRemovesOwnedDataset() {
        UUID id = UUID.randomUUID();
        NamedDataset existing = new NamedDataset().setUser(ALICE_EMAIL).setName("d1");
        existing.setUuid(id);
        when(repo.findByUuidAndUser(id, ALICE_EMAIL)).thenReturn(Optional.of(existing));

        service.delete(ALICE, id);

        verify(repo).delete(existing);
        verifyNoInteractions(links);
    }

    // Identity-completeness guard (moved here from the controller): a gateway-authenticated caller without an email owner key is
    // rejected with 401 before any repository access.

    @Test
    void nullUserIsUnauthorized() {
        PicsureException ex = assertThrows(PicsureException.class, () -> service.listForUser(null));
        assertThat(ex.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED);
        verifyNoInteractions(repo);
    }

    @Test
    void userWithoutEmailIsUnauthorized() {
        GatewayUser noEmail = new GatewayUser("auth0|alice", "auth0|alice", null, null, Set.of());
        PicsureException ex = assertThrows(PicsureException.class, () -> service.getForUser(noEmail, UUID.randomUUID()));
        assertThat(ex.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED);
        verifyNoInteractions(repo);
    }

    @Test
    void userWithBlankEmailIsUnauthorizedOnCreate() {
        GatewayUser blankEmail = new GatewayUser("auth0|alice", "auth0|alice", "   ", null, Set.of());
        NamedDatasetRequestDto req = new NamedDatasetRequestDto(UUID.randomUUID(), "d1", false, null);
        PicsureException ex = assertThrows(PicsureException.class, () -> service.create(blankEmail, req));
        assertThat(ex.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED);
        verifyNoInteractions(repo, queryRepo);
    }
}
