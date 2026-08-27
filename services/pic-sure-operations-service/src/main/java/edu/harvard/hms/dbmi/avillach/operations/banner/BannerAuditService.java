package edu.harvard.hms.dbmi.avillach.operations.banner;

import java.time.Instant;
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

    private static final Logger LOG = LoggerFactory.getLogger(BannerAuditService.class);

    private final LoggingClient loggingClient;

    public BannerAuditService(LoggingClient loggingClient) {
        this.loggingClient = loggingClient;
    }

    public void registerPublicationAudit(UUID bannerUuid, Instant timestamp, String presentationHash, String actor) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    loggingClient.send(
                        LoggingEvent.builder("BANNER").action("banner.published").caller(actor).metadata(
                            Map.of(
                                "bannerUuid", bannerUuid.toString(), "timestamp", timestamp.toString(), "presentationHash", presentationHash
                            )
                        ).build()
                    );
                } catch (RuntimeException e) {
                    LOG.warn("Banner {} was published, but its audit event could not be queued", bannerUuid, e);
                }
            }
        });
    }
}
