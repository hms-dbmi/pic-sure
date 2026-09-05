package edu.harvard.hms.dbmi.avillach.operations.banner;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity(name = "banner_occurrence")
@Table(name = "banner_occurrence")
public class BannerOccurrence {

    private static final Logger LOGGER = LoggerFactory.getLogger(BannerOccurrence.class);

    @Id
    @GeneratedValue
    @Column(columnDefinition = "BINARY(16)")
    private UUID uuid;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private BannerStatus status;

    @Column(name = "html_content", nullable = false, columnDefinition = "TEXT")
    private String htmlContent;

    @Column(length = 120)
    private String title;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private BannerAppearance appearance;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private BannerIcon icon;

    @Column(nullable = false)
    private boolean dismissible;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private BannerAudience audience;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private BannerPlacement placement;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "page_targets", nullable = false, columnDefinition = "JSON")
    private JsonNode pageTargets = BannerPageTargets.toStoredJson(List.of(BannerPageTarget.all()));

    @Column(name = "start_at")
    private Instant startAt;

    @Column(name = "end_at")
    private Instant endAt;

    private Integer priority;

    @Column(name = "presentation_hash", nullable = false, length = 64)
    private String presentationHash;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "created_by", nullable = false, length = 255)
    private String createdBy;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "updated_by", nullable = false, length = 255)
    private String updatedBy;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "published_by", length = 255)
    private String publishedBy;

    @Column(name = "disabled_at")
    private Instant disabledAt;

    @Column(name = "disabled_by", length = 255)
    private String disabledBy;

    @Column(name = "archived_at")
    private Instant archivedAt;

    @Column(name = "archived_by", length = 255)
    private String archivedBy;

    @Column(name = "restored_from_uuid", columnDefinition = "BINARY(16)")
    private UUID restoredFromUuid;

    public UUID getUuid() {
        return uuid;
    }

    public BannerStatus getStatus() {
        return status;
    }

    public BannerOccurrence setStatus(BannerStatus status) {
        this.status = status;
        return this;
    }

    public String getHtmlContent() {
        return htmlContent;
    }

    public BannerOccurrence setHtmlContent(String htmlContent) {
        this.htmlContent = htmlContent;
        return this;
    }

    public String getTitle() {
        return title;
    }

    public BannerOccurrence setTitle(String title) {
        this.title = title;
        return this;
    }

    public BannerAppearance getAppearance() {
        return appearance;
    }

    public BannerOccurrence setAppearance(BannerAppearance appearance) {
        this.appearance = appearance;
        return this;
    }

    public BannerIcon getIcon() {
        return icon;
    }

    public BannerOccurrence setIcon(BannerIcon icon) {
        this.icon = icon;
        return this;
    }

    public boolean isDismissible() {
        return dismissible;
    }

    public BannerOccurrence setDismissible(boolean dismissible) {
        this.dismissible = dismissible;
        return this;
    }

    public BannerAudience getAudience() {
        return audience;
    }

    public BannerOccurrence setAudience(BannerAudience audience) {
        this.audience = audience;
        return this;
    }

    public BannerPlacement getPlacement() {
        return placement;
    }

    public BannerOccurrence setPlacement(BannerPlacement placement) {
        this.placement = placement;
        return this;
    }

    public List<BannerPageTarget> getPageTargets() {
        List<BannerPageTarget> targets = BannerPageTargets.fromStoredJson(pageTargets);
        if (targets == null) {
            LOGGER.warn("Ignoring banner {} because its stored page targets are invalid", uuid);
        }
        return targets;
    }

    public BannerOccurrence setPageTargets(List<BannerPageTarget> pageTargets) {
        this.pageTargets = pageTargets == null ? null : BannerPageTargets.toStoredJson(pageTargets);
        return this;
    }

    public Instant getStartAt() {
        return startAt;
    }

    public BannerOccurrence setStartAt(Instant startAt) {
        this.startAt = startAt;
        return this;
    }

    public Instant getEndAt() {
        return endAt;
    }

    public BannerOccurrence setEndAt(Instant endAt) {
        this.endAt = endAt;
        return this;
    }

    public Integer getPriority() {
        return priority;
    }

    public BannerOccurrence setPriority(Integer priority) {
        this.priority = priority;
        return this;
    }

    public String getPresentationHash() {
        return presentationHash;
    }

    public BannerOccurrence setPresentationHash(String presentationHash) {
        this.presentationHash = presentationHash;
        return this;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public BannerOccurrence setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
        return this;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public BannerOccurrence setCreatedBy(String createdBy) {
        this.createdBy = createdBy;
        return this;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public BannerOccurrence setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
        return this;
    }

    public String getUpdatedBy() {
        return updatedBy;
    }

    public BannerOccurrence setUpdatedBy(String updatedBy) {
        this.updatedBy = updatedBy;
        return this;
    }

    public Instant getPublishedAt() {
        return publishedAt;
    }

    public BannerOccurrence setPublishedAt(Instant publishedAt) {
        this.publishedAt = publishedAt;
        return this;
    }

    public String getPublishedBy() {
        return publishedBy;
    }

    public BannerOccurrence setPublishedBy(String publishedBy) {
        this.publishedBy = publishedBy;
        return this;
    }

    public Instant getDisabledAt() {
        return disabledAt;
    }

    public BannerOccurrence setDisabledAt(Instant disabledAt) {
        this.disabledAt = disabledAt;
        return this;
    }

    public String getDisabledBy() {
        return disabledBy;
    }

    public BannerOccurrence setDisabledBy(String disabledBy) {
        this.disabledBy = disabledBy;
        return this;
    }

    public Instant getArchivedAt() {
        return archivedAt;
    }

    public BannerOccurrence setArchivedAt(Instant archivedAt) {
        this.archivedAt = archivedAt;
        return this;
    }

    public String getArchivedBy() {
        return archivedBy;
    }

    public BannerOccurrence setArchivedBy(String archivedBy) {
        this.archivedBy = archivedBy;
        return this;
    }

    public UUID getRestoredFromUuid() {
        return restoredFromUuid;
    }

    public BannerOccurrence setRestoredFromUuid(UUID restoredFromUuid) {
        this.restoredFromUuid = restoredFromUuid;
        return this;
    }
}
