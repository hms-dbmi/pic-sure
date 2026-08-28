package edu.harvard.hms.dbmi.avillach.operations.banner;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.Immutable;
import org.hibernate.type.SqlTypes;

import com.fasterxml.jackson.databind.JsonNode;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

@Entity(name = "banner_version")
@Immutable
@Table(
    name = "banner_version",
    uniqueConstraints = @UniqueConstraint(name = "uq_banner_version_number", columnNames = {"banner_uuid", "version_number"})
)
public class BannerVersion {

    @Id
    @GeneratedValue
    @Column(columnDefinition = "BINARY(16)")
    private UUID uuid;

    @Column(name = "banner_uuid", nullable = false, columnDefinition = "BINARY(16)")
    private UUID bannerUuid;

    @Column(name = "version_number", nullable = false)
    private int versionNumber;

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
    private JsonNode pageTargets;

    @Column(name = "start_at")
    private Instant startAt;

    @Column(name = "end_at")
    private Instant endAt;

    @Column(name = "presentation_hash", nullable = false, length = 64)
    private String presentationHash;

    @Column(name = "effective_at", nullable = false)
    private Instant effectiveAt;

    @Column(nullable = false, length = 255)
    private String actor;

    protected BannerVersion() {}

    private BannerVersion(BannerOccurrence banner, int versionNumber, Instant effectiveAt, String actor) {
        this.bannerUuid = banner.getUuid();
        this.versionNumber = versionNumber;
        this.htmlContent = banner.getHtmlContent();
        this.title = banner.getTitle();
        this.appearance = banner.getAppearance();
        this.icon = banner.getIcon();
        this.dismissible = banner.isDismissible();
        this.audience = banner.getAudience();
        this.placement = banner.getPlacement();
        this.pageTargets = banner.getPageTargets().deepCopy();
        this.startAt = banner.getStartAt();
        this.endAt = banner.getEndAt();
        this.presentationHash = banner.getPresentationHash();
        this.effectiveAt = effectiveAt;
        this.actor = actor;
    }

    static BannerVersion snapshot(BannerOccurrence banner, int versionNumber, Instant effectiveAt, String actor) {
        return new BannerVersion(banner, versionNumber, effectiveAt, actor);
    }

    public UUID getUuid() {
        return uuid;
    }

    public UUID getBannerUuid() {
        return bannerUuid;
    }

    public int getVersionNumber() {
        return versionNumber;
    }

    public String getHtmlContent() {
        return htmlContent;
    }

    public String getTitle() {
        return title;
    }

    public BannerAppearance getAppearance() {
        return appearance;
    }

    public BannerIcon getIcon() {
        return icon;
    }

    public boolean isDismissible() {
        return dismissible;
    }

    public BannerAudience getAudience() {
        return audience;
    }

    public BannerPlacement getPlacement() {
        return placement;
    }

    public JsonNode getPageTargets() {
        return pageTargets;
    }

    public Instant getStartAt() {
        return startAt;
    }

    public Instant getEndAt() {
        return endAt;
    }

    public String getPresentationHash() {
        return presentationHash;
    }

    public Instant getEffectiveAt() {
        return effectiveAt;
    }

    public String getActor() {
        return actor;
    }
}
