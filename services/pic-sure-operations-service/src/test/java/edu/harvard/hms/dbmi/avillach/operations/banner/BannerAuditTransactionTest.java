package edu.harvard.hms.dbmi.avillach.operations.banner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

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
import edu.harvard.hms.dbmi.avillach.commons.identity.GatewayUser;

@SpringBootTest
class BannerAuditTransactionTest {

    private static final GatewayUser ADMIN = new GatewayUser("admin-id", "admin-subject", "admin@example.org", "ADMIN", Set.of("ADMIN"));

    @Autowired
    private BannerService service;

    @Autowired
    private BannerRepository repository;

    @Autowired
    private TransactionTemplate transactions;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private LoggingClient loggingClient;

    @BeforeEach
    void cleanDatabaseAndMock() {
        repository.deleteAll();
        reset(loggingClient);
    }

    @Test
    void publishesOneAuditEventOnlyAfterTheDatabaseTransactionCommits() throws Exception {
        BannerDto[] published = new BannerDto[1];

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

    private PublishBannerRequest request() {
        return new PublishBannerRequest(
            "<p>Committed banner</p>", "Notice", BannerAppearance.PRIMARY, BannerIcon.INFORMATION, true, BannerAudience.EVERYONE,
            BannerPlacement.SITE_TOP, objectMapper.createArrayNode().add(objectMapper.createObjectNode().put("kind", "ALL"))
        );
    }
}
