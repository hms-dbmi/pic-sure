package edu.harvard.hms.dbmi.avillach.operations.banner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

import com.fasterxml.jackson.databind.ObjectMapper;

import edu.harvard.dbmi.avillach.logging.LoggingClient;
import edu.harvard.dbmi.avillach.logging.LoggingEvent;
import edu.harvard.hms.dbmi.avillach.commons.error.PicsureException;
import edu.harvard.hms.dbmi.avillach.commons.identity.GatewayUser;

@SpringBootTest
class BannerAuditTransactionTest {

    private static final GatewayUser ADMIN = new GatewayUser("admin-id", "admin-subject", "admin@example.org", "ADMIN", Set.of("ADMIN"));

    @Autowired
    private BannerPriorityAllocatorRepository allocatorRepository;

    @Autowired
    private BannerService service;

    @Autowired
    private BannerRepository repository;

    @Autowired
    private BannerVersionRepository versionRepository;

    @Autowired
    private TransactionTemplate transactions;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private LoggingClient loggingClient;

    @BeforeEach
    void cleanDatabaseAndMock() {
        versionRepository.deleteAll();
        repository.deleteAll();
        reset(loggingClient);
        allocatorRepository.saveAndFlush(
            new BannerPriorityAllocator().setId(BannerPriorityAllocator.SINGLETON_ID).setNextPriority(1)
        );
    }

    @Test
    void publishesOneAuditEventOnlyAfterTheDatabaseTransactionCommits() throws Exception {
        ManagementBannerDto[] published = new ManagementBannerDto[1];

        transactions.executeWithoutResult(status -> {
            published[0] = service.publish(request(), ADMIN);
            verifyNoInteractions(loggingClient);
        });

        ArgumentCaptor<LoggingEvent> event = ArgumentCaptor.forClass(LoggingEvent.class);
        verify(loggingClient).send(event.capture());
        assertThat(event.getValue().getEventType()).isEqualTo("BANNER");
        assertThat(event.getValue().getAction()).isEqualTo("banner.published");
        assertThat(event.getValue().getCaller()).isEqualTo("admin-id");
        assertThat(event.getValue().getMetadata()).containsExactlyInAnyOrderEntriesOf(
            Map.of(
                "bannerUuid", published[0].uuid().toString(), "timestamp", published[0].publishedAt().toString(), "presentationHash",
                published[0].presentationHash()
            )
        );
        assertThat(objectMapper.writeValueAsString(event.getValue())).doesNotContain("Committed banner");
    }

    @Test
    void rollbackDoesNotEmitAFalseSuccessfulPublicationAudit() {
        transactions.executeWithoutResult(status -> {
            service.publish(request(), ADMIN);
            status.setRollbackOnly();
        });

        assertThat(repository.count()).isZero();
        verifyNoInteractions(loggingClient);
    }

    @Test
    void auditDeliveryFailureCannotFailAnAlreadyCommittedPublication() {
        doThrow(new IllegalStateException("logging unavailable")).when(loggingClient).send(any());

        transactions.executeWithoutResult(status -> service.publish(request(), ADMIN));

        assertThat(repository.count()).isOne();
        verify(loggingClient).send(any());
    }

    @Test
    void savingAndUpdatingADraftEmitOneConciseEventEach() throws Exception {
        ManagementBannerDto[] saved = new ManagementBannerDto[1];

        transactions.executeWithoutResult(status -> saved[0] = service.saveDraft(request(), ADMIN));

        ArgumentCaptor<LoggingEvent> saveEvent = ArgumentCaptor.forClass(LoggingEvent.class);
        verify(loggingClient).send(saveEvent.capture());
        assertThat(saveEvent.getValue().getAction()).isEqualTo("banner.saved");
        assertThat(saveEvent.getValue().getMetadata()).containsKeys("bannerUuid", "timestamp", "presentationHash");
        assertThat(objectMapper.writeValueAsString(saveEvent.getValue())).doesNotContain("Committed banner");

        reset(loggingClient);
        PublishBannerRequest updated = new PublishBannerRequest(
            "<p>Updated draft</p>", "Updated", BannerAppearance.WARNING, BannerIcon.WARNING, false, BannerAudience.SIGNED_IN,
            BannerPlacement.SITE_TOP, List.of(BannerPageTarget.all())
        );

        transactions.executeWithoutResult(status -> service.update(saved[0].uuid(), updated, ADMIN));

        ArgumentCaptor<LoggingEvent> updateEvent = ArgumentCaptor.forClass(LoggingEvent.class);
        verify(loggingClient).send(updateEvent.capture());
        assertThat(updateEvent.getValue().getAction()).isEqualTo("banner.updated");
        assertThat(updateEvent.getValue().getMetadata()).containsKeys("bannerUuid", "timestamp", "presentationHash");
        assertThat(objectMapper.writeValueAsString(updateEvent.getValue())).doesNotContain("Updated draft");
    }

    @Test
    void materialUpdateEmitsOneConciseAuditAfterCommitAndNoOpEmitsNone() throws Exception {
        ManagementBannerDto published = service.publish(request(), ADMIN);
        reset(loggingClient);
        PublishBannerRequest changed = new PublishBannerRequest(
            "<p>Corrected banner</p>", "Notice", BannerAppearance.WARNING, BannerIcon.INFORMATION, true, BannerAudience.EVERYONE,
            BannerPlacement.SITE_TOP, List.of(BannerPageTarget.all())
        );
        ManagementBannerDto[] updated = new ManagementBannerDto[1];

        transactions.executeWithoutResult(status -> {
            updated[0] = service.update(published.uuid(), changed, ADMIN);
            verifyNoInteractions(loggingClient);
        });

        ArgumentCaptor<LoggingEvent> event = ArgumentCaptor.forClass(LoggingEvent.class);
        verify(loggingClient).send(event.capture());
        assertThat(event.getValue().getAction()).isEqualTo("banner.updated");
        assertThat(event.getValue().getCaller()).isEqualTo("admin-id");
        assertThat(event.getValue().getMetadata()).containsExactlyInAnyOrderEntriesOf(
            Map.of(
                "bannerUuid", published.uuid().toString(), "timestamp", updated[0].updatedAt().toString(), "previousPresentationHash",
                published.presentationHash(), "presentationHash", updated[0].presentationHash()
            )
        );
        assertThat(objectMapper.writeValueAsString(event.getValue())).doesNotContain("Corrected banner");

        reset(loggingClient);
        transactions.executeWithoutResult(status -> service.update(published.uuid(), changed, ADMIN));
        verifyNoInteractions(loggingClient);
    }

    @Test
    void reorderEmitsOneConciseAuditAfterCommit() throws Exception {
        ManagementBannerDto first = service.publish(request(), ADMIN);
        ManagementBannerDto second = service.publish(request(), ADMIN);
        ManagementBannerDto arrival = service.publish(request(), ADMIN);
        ManagementBannerDto departed = service.publish(request(), ADMIN);
        service.disable(departed.uuid(), ADMIN);
        reset(loggingClient);

        transactions.executeWithoutResult(status -> {
            service.reorder(List.of(departed.uuid(), second.uuid(), first.uuid()), ADMIN);
            verifyNoInteractions(loggingClient);
        });

        ArgumentCaptor<LoggingEvent> event = ArgumentCaptor.forClass(LoggingEvent.class);
        verify(loggingClient).send(event.capture());
        assertThat(event.getValue().getAction()).isEqualTo("banner.reordered");
        assertThat(event.getValue().getCaller()).isEqualTo("admin-id");
        assertThat(event.getValue().getMetadata()).containsEntry(
            "bannerUuids", List.of(second.uuid().toString(), first.uuid().toString(), arrival.uuid().toString())
        );
        assertThat(objectMapper.writeValueAsString(event.getValue())).doesNotContain("Committed banner");
    }

    @Test
    void disableEmitsOneConciseAuditAfterCommitAndARejectedTransitionEmitsNone() throws Exception {
        ManagementBannerDto published = service.publish(request(), ADMIN);
        reset(loggingClient);
        ManagementBannerDto[] disabled = new ManagementBannerDto[1];

        transactions.executeWithoutResult(status -> {
            disabled[0] = service.disable(published.uuid(), ADMIN);
            verifyNoInteractions(loggingClient);
        });

        ArgumentCaptor<LoggingEvent> event = ArgumentCaptor.forClass(LoggingEvent.class);
        verify(loggingClient).send(event.capture());
        assertThat(event.getValue().getEventType()).isEqualTo("BANNER");
        assertThat(event.getValue().getAction()).isEqualTo("banner.disabled");
        assertThat(event.getValue().getCaller()).isEqualTo("admin-id");
        assertThat(event.getValue().getMetadata()).containsExactlyInAnyOrderEntriesOf(
            Map.of(
                "bannerUuid", published.uuid().toString(), "timestamp", disabled[0].disabledAt().toString(), "presentationHash",
                published.presentationHash()
            )
        );
        assertThat(objectMapper.writeValueAsString(event.getValue())).doesNotContain("Committed banner");

        reset(loggingClient);
        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> service.disable(published.uuid(), ADMIN)))
            .isInstanceOf(PicsureException.class);
        verifyNoInteractions(loggingClient);
    }

    @Test
    void archiveEmitsOneConciseAuditAfterCommitAndARejectedTransitionEmitsNone() throws Exception {
        ManagementBannerDto published = service.publish(request(), ADMIN);
        service.disable(published.uuid(), ADMIN);
        reset(loggingClient);
        ArchivedBannerDto[] archived = new ArchivedBannerDto[1];

        transactions.executeWithoutResult(status -> {
            archived[0] = service.archive(published.uuid(), ADMIN);
            verifyNoInteractions(loggingClient);
        });

        ArgumentCaptor<LoggingEvent> event = ArgumentCaptor.forClass(LoggingEvent.class);
        verify(loggingClient).send(event.capture());
        assertThat(event.getValue().getEventType()).isEqualTo("BANNER");
        assertThat(event.getValue().getAction()).isEqualTo("banner.archived");
        assertThat(event.getValue().getCaller()).isEqualTo("admin-id");
        assertThat(event.getValue().getMetadata()).containsExactlyInAnyOrderEntriesOf(
            Map.of(
                "bannerUuid", published.uuid().toString(), "timestamp", archived[0].archivedAt().toString(), "presentationHash",
                published.presentationHash()
            )
        );
        assertThat(objectMapper.writeValueAsString(event.getValue())).doesNotContain("Committed banner");

        reset(loggingClient);
        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> service.archive(published.uuid(), ADMIN)))
            .isInstanceOf(PicsureException.class);
        verifyNoInteractions(loggingClient);
    }

    @Test
    void rollbackDoesNotEmitAFalseArchiveAudit() {
        ManagementBannerDto saved = service.saveDraft(request(), ADMIN);
        reset(loggingClient);

        transactions.executeWithoutResult(status -> {
            service.archive(saved.uuid(), ADMIN);
            status.setRollbackOnly();
        });

        assertThat(repository.findById(saved.uuid()).orElseThrow().getStatus()).isEqualTo(BannerStatus.SAVED);
        verifyNoInteractions(loggingClient);
    }

    @Test
    void restoreEmitsOneConciseAuditAfterCommitWithoutCreateOrArchiveCompanions() throws Exception {
        ManagementBannerDto published = service.publish(request(), ADMIN);
        service.disable(published.uuid(), ADMIN);
        reset(loggingClient);
        ManagementBannerDto[] restored = new ManagementBannerDto[1];

        transactions.executeWithoutResult(status -> {
            restored[0] = service.restore(published.uuid(), request(), ADMIN);
            verifyNoInteractions(loggingClient);
        });

        ArgumentCaptor<LoggingEvent> event = ArgumentCaptor.forClass(LoggingEvent.class);
        verify(loggingClient).send(event.capture());
        assertThat(event.getValue().getEventType()).isEqualTo("BANNER");
        assertThat(event.getValue().getAction()).isEqualTo("banner.restored");
        assertThat(event.getValue().getCaller()).isEqualTo("admin-id");
        assertThat(event.getValue().getMetadata()).containsExactlyInAnyOrderEntriesOf(
            Map.of(
                "bannerUuid", restored[0].uuid().toString(), "sourceBannerUuid", published.uuid().toString(), "newBannerUuid",
                restored[0].uuid().toString(), "timestamp", restored[0].publishedAt().toString(), "presentationHash",
                restored[0].presentationHash()
            )
        );
        assertThat(objectMapper.writeValueAsString(event.getValue())).doesNotContain("Committed banner", "htmlContent", "<p>");
    }

    @Test
    void rolledBackRestoreLeavesTheSourceDisabledAndEmitsNoAudit() {
        ManagementBannerDto published = service.publish(request(), ADMIN);
        service.disable(published.uuid(), ADMIN);
        reset(loggingClient);

        transactions.executeWithoutResult(status -> {
            service.restore(published.uuid(), request(), ADMIN);
            status.setRollbackOnly();
        });

        assertThat(repository.findById(published.uuid()).orElseThrow().getStatus()).isEqualTo(BannerStatus.DISABLED);
        assertThat(repository.count()).isOne();
        assertThat(versionRepository.findAll()).hasSize(1);
        verifyNoInteractions(loggingClient);
    }

    private PublishBannerRequest request() {
        return new PublishBannerRequest(
            "<p>Committed banner</p>", "Notice", BannerAppearance.PRIMARY, BannerIcon.INFORMATION, true, BannerAudience.EVERYONE,
            BannerPlacement.SITE_TOP, List.of(BannerPageTarget.all())
        );
    }
}
