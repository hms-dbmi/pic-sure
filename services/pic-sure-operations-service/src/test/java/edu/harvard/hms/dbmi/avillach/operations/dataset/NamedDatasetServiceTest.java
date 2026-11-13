package edu.harvard.hms.dbmi.avillach.operations.dataset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.AdditionalAnswers.returnsFirstArg;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;

import edu.harvard.hms.dbmi.avillach.commons.error.PicsureException;
import edu.harvard.hms.dbmi.avillach.commons.identity.GatewayUser;
import edu.harvard.hms.dbmi.avillach.operations.query.Query;
import edu.harvard.hms.dbmi.avillach.operations.query.QueryRepository;
import edu.harvard.hms.dbmi.avillach.operations.query.QueryVariantRow;

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
 * <p>The repositories are mocks, so these tests pin what the service decides, which rows it asks the repositories to write, and which
 * general queries and owner it asks for the variants of. Which query rows the database returns for them is pinned by
 * {@code NamedDatasetControllerTest} and {@code NamedDatasetMySqlTest}.
 */
@ExtendWith(MockitoExtension.class)
class NamedDatasetServiceTest {

    private static final String ALICE_EMAIL = "alice@example.com";
    private static final UUID ALICE_ID = UUID.fromString("5f0c1f0e-6f0a-4a57-9d6e-0d1c6a3b9f11");
    private static final UUID BOB_ID = UUID.fromString("7c1b1f0e-2a3b-4c4d-8e5f-0a1b2c3d4e5f");

    /** Gateway-resolved identity for the canonical caller; the service keys datasets on {@code getEmail()}. */
    static final GatewayUser ALICE = new GatewayUser(ALICE_ID.toString(), "auth0|alice", ALICE_EMAIL, null, Set.of());

    private static final String STORED_BODY = "{\"query\":{\"select\":[],\"phenotypicClause\":null,\"expectedResultType\":\"DATAFRAME\"}}";

    @Mock
    private NamedDatasetRepository repo;

    @Mock
    private QueryRepository queryRepo;

    private NamedDatasetService service;

    @BeforeEach
    void buildService() {
        service = new NamedDatasetService(repo, queryRepo, new NamedDatasetMapper());
    }

    private static Query storedQuery(UUID owner, UUID generalQuery) {
        Query query = new Query();
        query.setUuid(UUID.randomUUID());
        query.setQuery(STORED_BODY).setVersion("3");
        query.setUserUuid(owner);
        query.setQueryToVariantUuid(generalQuery);
        return query;
    }

    private static Query ownerlessQuery() {
        return storedQuery(null, null);
    }

    private static NamedDataset datasetPointingAt(Query query) {
        NamedDataset dataset = new NamedDataset().setUser(ALICE_EMAIL).setName("d1").setQuery(query).setArchived(false);
        dataset.setQueryToVariantUuid(query == null ? null : query.getQueryToVariantUuid());
        dataset.setUuid(UUID.randomUUID());
        return dataset;
    }

    private static NamedDatasetRequestDto requestNaming(Query query) {
        return new NamedDatasetRequestDto(query.getUuid(), "d1", false, null);
    }

    private void callerOwns(NamedDataset dataset) {
        when(repo.findByUuidAndUser(dataset.getUuid(), ALICE_EMAIL)).thenReturn(Optional.of(dataset));
    }

    private void queryIsStored(Query query) {
        when(queryRepo.findById(query.getUuid())).thenReturn(Optional.of(query));
    }

    private void savingAssignsAnId() {
        when(repo.saveAndFlush(any())).thenAnswer(inv -> {
            NamedDataset e = inv.getArgument(0);
            e.setUuid(UUID.randomUUID());
            return e;
        });
    }

    private NamedDataset savedDataset() {
        ArgumentCaptor<NamedDataset> saved = ArgumentCaptor.forClass(NamedDataset.class);
        verify(repo).saveAndFlush(saved.capture());
        return saved.getValue();
    }

    private void assertNothingWasSaved() {
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
    void readListsEveryVariantOfTheGeneralQuery() {
        UUID firstGeneral = UUID.randomUUID();
        UUID secondGeneral = UUID.randomUUID();
        Query first = storedQuery(ALICE_ID, firstGeneral);
        Query second = storedQuery(ALICE_ID, secondGeneral);
        UUID firstSample = UUID.randomUUID();
        UUID firstPfb = UUID.randomUUID();
        NamedDataset one = datasetPointingAt(first);
        NamedDataset two = datasetPointingAt(second);
        when(repo.findByUser(ALICE_EMAIL)).thenReturn(List.of(one, two));
        when(queryRepo.findVariantsOf(Set.of(firstGeneral, secondGeneral), ALICE_ID)).thenReturn(
            List.of(
                new QueryVariantRow(firstGeneral, first.getUuid(), false, "DATAFRAME"),
                new QueryVariantRow(secondGeneral, second.getUuid(), false, "DATAFRAME"),
                new QueryVariantRow(firstGeneral, firstPfb, false, "DATAFRAME_PFB"),
                new QueryVariantRow(firstGeneral, firstSample, true, "DATAFRAME")
            )
        );

        List<NamedDatasetDto> result = service.listForUser(ALICE);

        assertThat(result.get(0).queries()).containsExactly(
            new LinkedQueryDto(first.getUuid(), false, "DATAFRAME"), new LinkedQueryDto(firstPfb, false, "DATAFRAME_PFB"),
            new LinkedQueryDto(firstSample, true, "DATAFRAME")
        );
        assertThat(result.get(1).queries()).containsExactly(new LinkedQueryDto(second.getUuid(), false, "DATAFRAME"));
        assertThat(result).extracting(dto -> dto.query().uuid()).containsExactly(first.getUuid(), second.getUuid());
    }

    @Test
    void readOfADatasetWithNoGeneralQueryListsNoQueries() {
        UUID general = UUID.randomUUID();
        Query owned = storedQuery(ALICE_ID, general);
        NamedDataset onAnOwnerlessRow = datasetPointingAt(ownerlessQuery());
        NamedDataset onAnOwnedRow = datasetPointingAt(owned);
        when(repo.findByUser(ALICE_EMAIL)).thenReturn(List.of(onAnOwnerlessRow, onAnOwnedRow));
        when(queryRepo.findVariantsOf(Set.of(general), ALICE_ID))
            .thenReturn(List.of(new QueryVariantRow(general, owned.getUuid(), false, "DATAFRAME")));

        List<NamedDatasetDto> result = service.listForUser(ALICE);

        assertThat(result.get(0).queries()).isEmpty();
        assertThat(result.get(0).query().uuid()).isEqualTo(onAnOwnerlessRow.getQuery().getUuid());
        assertThat(result.get(1).queries()).containsExactly(new LinkedQueryDto(owned.getUuid(), false, "DATAFRAME"));
    }

    @Test
    void readOfDatasetsThatAllHaveNoGeneralQueryAsksForNoVariants() {
        NamedDataset dataset = datasetPointingAt(ownerlessQuery());
        callerOwns(dataset);

        NamedDatasetDto dto = service.getForUser(ALICE, dataset.getUuid());

        assertThat(dto.queries()).isEmpty();
        verifyNoInteractions(queryRepo);
    }

    @Test
    void readListsOnlyTheCallersRows() {
        UUID general = UUID.randomUUID();
        NamedDataset dataset = datasetPointingAt(storedQuery(BOB_ID, general));
        callerOwns(dataset);
        when(queryRepo.findVariantsOf(Set.of(general), ALICE_ID)).thenReturn(List.of());

        NamedDatasetDto dto = service.getForUser(ALICE, dataset.getUuid());

        assertThat(dto.queries()).isEmpty();
        assertThat(dto.query().uuid()).isEqualTo(dataset.getQuery().getUuid());
        verify(queryRepo).findVariantsOf(Set.of(general), ALICE_ID);
    }

    @Test
    void readMatchesTheCallersRowsWhateverTheLetterCaseOfTheGatewayUserId() {
        GatewayUser upperCase = new GatewayUser(ALICE_ID.toString().toUpperCase(), "auth0|alice", ALICE_EMAIL, null, Set.of());
        UUID general = UUID.randomUUID();
        Query full = storedQuery(ALICE_ID, general);
        NamedDataset dataset = datasetPointingAt(full);
        callerOwns(dataset);
        when(queryRepo.findVariantsOf(Set.of(general), ALICE_ID))
            .thenReturn(List.of(new QueryVariantRow(general, full.getUuid(), false, "DATAFRAME")));

        NamedDatasetDto dto = service.getForUser(upperCase, dataset.getUuid());

        assertThat(dto.queries()).containsExactly(new LinkedQueryDto(full.getUuid(), false, "DATAFRAME"));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(
        strings = {"auth0|alice", "open-access-host", "urn:uuid:5f0c1f0e-6f0a-4a57-9d6e-0d1c6a3b9f11",
            "5f0c1f0e-6f0a-4a57-9d6e-0d1c6a3b9f1", "1-1-1-1-1"}
    )
    void readForACallerWhoseGatewayUserIdIsNotACanonicalUuidListsNoQueries(String userId) {
        GatewayUser caller = new GatewayUser(userId, "subject", ALICE_EMAIL, null, Set.of());
        NamedDataset dataset = datasetPointingAt(storedQuery(ALICE_ID, UUID.randomUUID()));
        callerOwns(dataset);
        when(repo.findByUser(ALICE_EMAIL)).thenReturn(List.of(dataset));

        assertThat(service.getForUser(caller, dataset.getUuid()).queries()).isEmpty();
        assertThat(service.listForUser(caller)).singleElement().extracting(NamedDatasetDto::queries).isEqualTo(List.of());
        verifyNoInteractions(queryRepo);
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
    void createSetsTheGeneralQueryFromItsRow() {
        UUID general = UUID.randomUUID();
        Query pfb = storedQuery(ALICE_ID, general);
        UUID sample = UUID.randomUUID();
        queryIsStored(pfb);
        savingAssignsAnId();
        when(queryRepo.findVariantsOf(Set.of(general), ALICE_ID)).thenReturn(
            List.of(new QueryVariantRow(general, pfb.getUuid(), false, "DATAFRAME_PFB"), new QueryVariantRow(general, sample, true, null))
        );

        NamedDatasetDto dto = service.create(ALICE, requestNaming(pfb));

        NamedDataset saved = savedDataset();
        assertThat(saved.getQuery()).isSameAs(pfb);
        assertThat(saved.getQueryToVariantUuid()).isEqualTo(general);
        assertThat(dto.query().uuid()).isEqualTo(pfb.getUuid());
        assertThat(dto.queries())
            .containsExactly(new LinkedQueryDto(pfb.getUuid(), false, "DATAFRAME_PFB"), new LinkedQueryDto(sample, true, null));
    }

    @Test
    void creatingADatasetOnAnotherUsersQueryIs403AndWritesNothing() {
        Query bobs = storedQuery(BOB_ID, UUID.randomUUID());
        queryIsStored(bobs);

        PicsureException ex = assertThrows(PicsureException.class, () -> service.create(ALICE, requestNaming(bobs)));

        assertThat(ex.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(ex.getErrorType()).isEqualTo("forbidden");
        assertNothingWasSaved();
    }

    @Test
    void creatingADatasetOnAnOwnedQueryIs403ForACallerWhoseGatewayUserIdIsNotAUuid() {
        GatewayUser subjectOnly = new GatewayUser("auth0|alice", "auth0|alice", ALICE_EMAIL, null, Set.of());
        Query alices = storedQuery(ALICE_ID, UUID.randomUUID());
        queryIsStored(alices);

        PicsureException ex = assertThrows(PicsureException.class, () -> service.create(subjectOnly, requestNaming(alices)));

        assertThat(ex.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
        assertNothingWasSaved();
    }

    @Test
    void creatingADatasetOnAnOwnerlessQueryIsAllowedAndHasNoGeneralQuery() {
        Query ownerless = ownerlessQuery();
        queryIsStored(ownerless);
        savingAssignsAnId();

        NamedDatasetDto dto = service.create(ALICE, requestNaming(ownerless));

        assertThat(dto.query().uuid()).isEqualTo(ownerless.getUuid());
        assertThat(dto.queries()).isEmpty();
        assertThat(savedDataset().getQueryToVariantUuid()).isNull();
        verify(queryRepo, never()).findVariantsOf(any(), any());
    }

    @Test
    void creatingADatasetOnTheCallersOwnQueryIsAllowed() {
        Query alices = storedQuery(ALICE_ID, UUID.randomUUID());
        queryIsStored(alices);
        savingAssignsAnId();

        NamedDatasetDto dto = service.create(ALICE, requestNaming(alices));

        assertThat(dto.query().uuid()).isEqualTo(alices.getUuid());
        assertThat(savedDataset().getQuery()).isSameAs(alices);
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
        verifyNoInteractions(queryRepo);
    }

    @Test
    void putWithAnotherQueryMovesQueryIdAndTheGeneralQuery() {
        UUID oldGeneral = UUID.randomUUID();
        UUID newGeneral = UUID.randomUUID();
        Query old = storedQuery(ALICE_ID, oldGeneral);
        Query other = storedQuery(ALICE_ID, newGeneral);
        NamedDataset dataset = datasetPointingAt(old);
        callerOwns(dataset);
        queryIsStored(other);
        when(repo.saveAndFlush(any())).thenAnswer(returnsFirstArg());
        when(queryRepo.findVariantsOf(Set.of(newGeneral), ALICE_ID))
            .thenReturn(List.of(new QueryVariantRow(newGeneral, other.getUuid(), false, "COUNT")));

        NamedDatasetDto dto = service.update(ALICE, dataset.getUuid(), new NamedDatasetRequestDto(other.getUuid(), "renamed", true, null));

        NamedDataset saved = savedDataset();
        assertThat(saved).isSameAs(dataset);
        assertThat(saved.getQuery()).isSameAs(other);
        assertThat(saved.getQueryToVariantUuid()).isEqualTo(newGeneral);
        assertThat(dto.query().uuid()).isEqualTo(other.getUuid());
        assertThat(dto.queries()).containsExactly(new LinkedQueryDto(other.getUuid(), false, "COUNT"));
        assertThat(dto.name()).isEqualTo("renamed");
        assertThat(dto.archived()).isTrue();
    }

    @Test
    void putOntoAnotherUsersQueryIs403AndChangesNothing() {
        UUID general = UUID.randomUUID();
        Query full = storedQuery(ALICE_ID, general);
        Query bobs = storedQuery(BOB_ID, UUID.randomUUID());
        NamedDataset dataset = datasetPointingAt(full);
        callerOwns(dataset);
        queryIsStored(bobs);

        PicsureException ex = assertThrows(
            PicsureException.class,
            () -> service.update(ALICE, dataset.getUuid(), new NamedDatasetRequestDto(bobs.getUuid(), "renamed", true, null))
        );

        assertThat(ex.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(ex.getErrorType()).isEqualTo("forbidden");
        assertThat(dataset.getQuery()).isSameAs(full);
        assertThat(dataset.getQueryToVariantUuid()).isEqualTo(general);
        assertThat(dataset.getName()).isEqualTo("d1");
        assertThat(dataset.getArchived()).isFalse();
        assertNothingWasSaved();
    }

    @Test
    void putOntoAnOwnedQueryIs403ForACallerWhoseGatewayUserIdIsNotAUuid() {
        GatewayUser subjectOnly = new GatewayUser("auth0|alice", "auth0|alice", ALICE_EMAIL, null, Set.of());
        Query full = storedQuery(ALICE_ID, UUID.randomUUID());
        Query other = storedQuery(ALICE_ID, UUID.randomUUID());
        NamedDataset dataset = datasetPointingAt(full);
        callerOwns(dataset);
        queryIsStored(other);

        PicsureException ex =
            assertThrows(PicsureException.class, () -> service.update(subjectOnly, dataset.getUuid(), requestNaming(other)));

        assertThat(ex.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(dataset.getQuery()).isSameAs(full);
        assertNothingWasSaved();
    }

    @Test
    void putOntoTheCallersOwnQueryIgnoresTheLetterCaseOfTheGatewayUserId() {
        GatewayUser upperCase = new GatewayUser(ALICE_ID.toString().toUpperCase(), "auth0|alice", ALICE_EMAIL, null, Set.of());
        Query full = storedQuery(ALICE_ID, UUID.randomUUID());
        Query other = storedQuery(ALICE_ID, UUID.randomUUID());
        NamedDataset dataset = datasetPointingAt(full);
        callerOwns(dataset);
        queryIsStored(other);
        when(repo.saveAndFlush(any())).thenAnswer(returnsFirstArg());

        NamedDatasetDto dto = service.update(upperCase, dataset.getUuid(), requestNaming(other));

        assertThat(dto.query().uuid()).isEqualTo(other.getUuid());
        assertThat(dataset.getQueryToVariantUuid()).isEqualTo(other.getQueryToVariantUuid());
    }

    @Test
    void putOntoAnOwnerlessQueryMovesQueryIdAndClearsTheGeneralQuery() {
        Query full = storedQuery(ALICE_ID, UUID.randomUUID());
        Query ownerless = ownerlessQuery();
        NamedDataset dataset = datasetPointingAt(full);
        callerOwns(dataset);
        queryIsStored(ownerless);
        when(repo.saveAndFlush(any())).thenAnswer(returnsFirstArg());

        NamedDatasetDto dto = service.update(ALICE, dataset.getUuid(), requestNaming(ownerless));

        NamedDataset saved = savedDataset();
        assertThat(saved.getQuery()).isSameAs(ownerless);
        assertThat(saved.getQueryToVariantUuid()).isNull();
        assertThat(dto.query().uuid()).isEqualTo(ownerless.getUuid());
        assertThat(dto.queries()).isEmpty();
        verify(queryRepo, never()).findVariantsOf(any(), any());
    }

    @Test
    void putOntoADatasetThatPointsAtNoQueryPointsItAtTheQuery() {
        UUID general = UUID.randomUUID();
        Query full = storedQuery(ALICE_ID, general);
        NamedDataset dataset = datasetPointingAt(null);
        callerOwns(dataset);
        queryIsStored(full);
        when(repo.saveAndFlush(any())).thenAnswer(returnsFirstArg());

        NamedDatasetDto dto = service.update(ALICE, dataset.getUuid(), requestNaming(full));

        assertThat(dataset.getQuery()).isSameAs(full);
        assertThat(dataset.getQueryToVariantUuid()).isEqualTo(general);
        assertThat(dto.query().uuid()).isEqualTo(full.getUuid());
    }

    @Test
    void putWithTheSameQueryOnlyRenames() {
        UUID general = UUID.randomUUID();
        Query full = storedQuery(ALICE_ID, general);
        UUID sample = UUID.randomUUID();
        NamedDataset dataset = datasetPointingAt(full);
        callerOwns(dataset);
        when(repo.saveAndFlush(any())).thenAnswer(returnsFirstArg());
        when(queryRepo.findVariantsOf(Set.of(general), ALICE_ID)).thenReturn(
            List.of(
                new QueryVariantRow(general, full.getUuid(), false, "DATAFRAME"), new QueryVariantRow(general, sample, true, "DATAFRAME")
            )
        );

        NamedDatasetDto dto = service.update(ALICE, dataset.getUuid(), new NamedDatasetRequestDto(full.getUuid(), "renamed", false, null));

        assertThat(dto.name()).isEqualTo("renamed");
        assertThat(dto.query().uuid()).isEqualTo(full.getUuid());
        assertThat(dto.queries())
            .containsExactly(new LinkedQueryDto(full.getUuid(), false, "DATAFRAME"), new LinkedQueryDto(sample, true, "DATAFRAME"));
        assertThat(dataset.getQuery()).isSameAs(full);
        assertThat(dataset.getQueryToVariantUuid()).isEqualTo(general);
        verify(queryRepo, never()).findById(any());
    }

    @Test
    void putWithTheSameQueryTakesTheGeneralQueryOfItsRowWhenTheDatasetHasNone() {
        UUID general = UUID.randomUUID();
        Query full = storedQuery(ALICE_ID, general);
        NamedDataset dataset = datasetPointingAt(full).setQueryToVariantUuid(null);
        callerOwns(dataset);
        when(repo.saveAndFlush(any())).thenAnswer(returnsFirstArg());
        when(queryRepo.findVariantsOf(Set.of(general), ALICE_ID))
            .thenReturn(List.of(new QueryVariantRow(general, full.getUuid(), false, "DATAFRAME")));

        NamedDatasetDto dto = service.update(ALICE, dataset.getUuid(), requestNaming(full));

        assertThat(savedDataset().getQueryToVariantUuid()).isEqualTo(general);
        assertThat(dto.queries()).containsExactly(new LinkedQueryDto(full.getUuid(), false, "DATAFRAME"));
    }

    @Test
    void putWithTheSameQueryMakesNoOwnerCheck() {
        UUID general = UUID.randomUUID();
        Query bobs = storedQuery(BOB_ID, general);
        NamedDataset dataset = datasetPointingAt(bobs);
        callerOwns(dataset);
        when(repo.saveAndFlush(any())).thenAnswer(returnsFirstArg());

        NamedDatasetDto dto = service.update(ALICE, dataset.getUuid(), new NamedDatasetRequestDto(bobs.getUuid(), "renamed", false, null));

        assertThat(dto.name()).isEqualTo("renamed");
        assertThat(dto.query().uuid()).isEqualTo(bobs.getUuid());
        assertThat(dto.queries()).isEmpty();
        verify(queryRepo, never()).findById(any());
    }

    @Test
    void putOntoAQueryTheCallerAlreadyNamedIs409() {
        Query full = storedQuery(ALICE_ID, UUID.randomUUID());
        Query alreadyNamed = storedQuery(ALICE_ID, UUID.randomUUID());
        NamedDataset dataset = datasetPointingAt(full);
        callerOwns(dataset);
        queryIsStored(alreadyNamed);
        when(repo.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("unique_queryId_user"));

        PicsureException ex =
            assertThrows(PicsureException.class, () -> service.update(ALICE, dataset.getUuid(), requestNaming(alreadyNamed)));

        assertThat(ex.getStatus()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(ex).hasMessage("A NamedDataset for query " + alreadyNamed.getUuid() + " and user '" + ALICE_EMAIL + "' already exists");
    }

    @Test
    void putWithAQueryThatIsNotStoredIs404() {
        Query full = storedQuery(ALICE_ID, UUID.randomUUID());
        NamedDataset dataset = datasetPointingAt(full);
        callerOwns(dataset);
        UUID unknown = UUID.randomUUID();
        when(queryRepo.findById(unknown)).thenReturn(Optional.empty());

        PicsureException ex = assertThrows(
            PicsureException.class, () -> service.update(ALICE, dataset.getUuid(), new NamedDatasetRequestDto(unknown, "d1", false, null))
        );

        assertThat(ex.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(dataset.getQuery()).isSameAs(full);
        assertNothingWasSaved();
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
        verifyNoInteractions(queryRepo);
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
