package edu.harvard.hms.dbmi.avillach.operations.banner;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import edu.harvard.dbmi.avillach.logging.LoggingClient;
import edu.harvard.dbmi.avillach.logging.LoggingEvent;
import edu.harvard.hms.dbmi.avillach.commons.error.PicsureException;
import edu.harvard.hms.dbmi.avillach.commons.identity.GatewayUser;

@Service
public class BannerService {

    private static final Logger LOG = LoggerFactory.getLogger(BannerService.class);

    private final BannerRepository repository;
    private final Clock clock;
    private final BannerPresentationHasher hasher;
    private final LoggingClient loggingClient;

    public BannerService(
        BannerRepository repository, @Qualifier("bannerClock") Clock clock, BannerPresentationHasher hasher, LoggingClient loggingClient
    ) {
        this.repository = repository;
        this.clock = clock;
        this.hasher = hasher;
        this.loggingClient = loggingClient;
    }

    @Transactional(readOnly = true)
    public List<ActiveBannerDto> activeBanners() {
        Instant now = clock.instant();
        return repository.findActive(now).stream().map(ActiveBannerDto::from).toList();
    }

    @Transactional
    public BannerDto publish(PublishBannerRequest request, GatewayUser user) {
        if (!request.pageTargets().isArray()) {
            throw new PicsureException(org.springframework.http.HttpStatus.BAD_REQUEST, "bad_request", "Page targets must be an array");
        }
        Instant now = clock.instant();
        String actor = user.getUserId();
        String normalizedTitle = BannerPresentationHasher.normalizeTitle(request.title());
        BannerOccurrence banner = new BannerOccurrence().setStatus(BannerStatus.PUBLISHED).setHtmlContent(request.htmlContent())
            .setTitle(normalizedTitle.isEmpty() ? null : normalizedTitle).setAppearance(request.appearance()).setIcon(request.icon())
            .setDismissible(request.dismissible()).setAudience(request.audience()).setPlacement(request.placement())
            .setPageTargets(request.pageTargets().deepCopy()).setStartAt(now).setPriority(repository.findMaximumOrderablePriority(now) + 1)
            .setPresentationHash(hasher.hash(request)).setCreatedAt(now).setCreatedBy(actor).setUpdatedAt(now).setUpdatedBy(actor)
            .setPublishedAt(now).setPublishedBy(actor);
        BannerOccurrence saved = repository.saveAndFlush(banner);
        try {
            loggingClient.send(
                LoggingEvent.builder("BANNER").action("banner.published")
                    .metadata(
                        Map.of(
                            "actor", actor, "bannerUuid", saved.getUuid().toString(), "timestamp", now.toString(), "presentationHash",
                            saved.getPresentationHash()
                        )
                    ).build()
            );
        } catch (RuntimeException e) {
            LOG.warn("Banner {} was published, but its audit event could not be queued", saved.getUuid(), e);
        }
        return BannerDto.from(saved);
    }
}
