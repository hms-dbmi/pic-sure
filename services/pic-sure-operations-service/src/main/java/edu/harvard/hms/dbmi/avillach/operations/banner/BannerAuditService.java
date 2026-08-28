package edu.harvard.hms.dbmi.avillach.operations.banner;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import edu.harvard.dbmi.avillach.logging.LoggingClient;
import edu.harvard.dbmi.avillach.logging.LoggingEvent;

@Service
public class BannerAuditService {

    static final String SAVED_ACTION = "banner.saved";
    static final String UPDATED_ACTION = "banner.updated";
    static final String PUBLISHED_ACTION = "banner.published";
    static final String SCHEDULED_ACTION = "banner.scheduled";
    static final String REORDERED_ACTION = "banner.reordered";
    static final String DISABLED_ACTION = "banner.disabled";
    static final String ARCHIVED_ACTION = "banner.archived";

    private static final Logger LOG = LoggerFactory.getLogger(BannerAuditService.class);

    private final LoggingClient loggingClient;

    public BannerAuditService(LoggingClient loggingClient) {
        this.loggingClient = loggingClient;
    }

    public void registerMutationAudit(String action, UUID bannerUuid, Instant timestamp, String presentationHash, String actor) {
        LoggingEvent event = LoggingEvent.builder("BANNER").action(action).caller(actor)
            .metadata(Map.of("bannerUuid", bannerUuid.toString(), "timestamp", timestamp.toString(), "presentationHash", presentationHash))
            .build();
        registerAfterCommit(event, bannerUuid, action);
    }

    public void registerUpdateAudit(UUID bannerUuid, Instant timestamp, String previousHash, String presentationHash, String actor) {
        LoggingEvent event = LoggingEvent.builder("BANNER").action(UPDATED_ACTION).caller(actor)
            .metadata(
                Map.of(
                    "bannerUuid", bannerUuid.toString(), "timestamp", timestamp.toString(), "previousPresentationHash", previousHash,
                    "presentationHash", presentationHash
                )
            ).build();
        registerAfterCommit(event, bannerUuid, UPDATED_ACTION);
    }

    public void registerReorderAudit(List<UUID> bannerUuids, Instant timestamp, String actor) {
        List<String> orderedUuids = bannerUuids.stream().map(UUID::toString).toList();
        LoggingEvent event = LoggingEvent.builder("BANNER").action(REORDERED_ACTION).caller(actor)
            .metadata(Map.of("bannerUuids", orderedUuids, "timestamp", timestamp.toString())).build();
        registerAfterCommit(event, "queue", REORDERED_ACTION);
    }

    private void registerAfterCommit(LoggingEvent event, UUID bannerUuid, String action) {
        registerAfterCommit(event, bannerUuid.toString(), action);
    }

    private void registerAfterCommit(LoggingEvent event, String subject, String action) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    loggingClient.send(event);
                } catch (RuntimeException e) {
                    LOG.warn("Banner {} was changed, but its {} audit event could not be queued", subject, action, e);
                }
            }
        });
    }
}
