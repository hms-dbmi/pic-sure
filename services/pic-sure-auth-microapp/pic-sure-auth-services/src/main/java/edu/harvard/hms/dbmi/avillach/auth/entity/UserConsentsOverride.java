package edu.harvard.hms.dbmi.avillach.auth.entity;

import jakarta.persistence.*;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * <p>A {@link ConsentsOverride} for a single user that overrides the consents they would normally be granted.</p>
 */
@Entity(name = "user_consents_override")
public class UserConsentsOverride extends BaseEntity {

    @Column(unique = true, nullable = false, name = "user_id")
    private UUID userId;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "consents_override_id", nullable = false)
    private ConsentsOverride consentsOverride;

    @Column(nullable = false)
    private boolean enabled = true;

    public UUID getUserId() {
        return userId;
    }

    public UserConsentsOverride setUserId(UUID userId) {
        this.userId = userId;
        return this;
    }

    public ConsentsOverride getConsentsOverride() {
        return consentsOverride;
    }

    public UserConsentsOverride setConsentsOverride(ConsentsOverride consentsOverride) {
        this.consentsOverride = consentsOverride;
        return this;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public UserConsentsOverride setEnabled(boolean enabled) {
        this.enabled = enabled;
        return this;
    }

}
