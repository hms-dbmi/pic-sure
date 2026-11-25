package edu.harvard.hms.dbmi.avillach.auth.entity;

import jakarta.persistence.*;

import java.util.UUID;

/**
 * Assigns a {@link TestProfile} to a user. Changes to the assignment take effect at the user's next login.
 */
@Entity(name = "user_test_profile")
public class UserTestProfile extends BaseEntity {

    @Column(unique = true, nullable = false, name = "user_id")
    private UUID userId;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "test_profile_id", nullable = false)
    private TestProfile testProfile;

    @Column(nullable = false)
    private boolean enabled = true;

    public UUID getUserId() {
        return userId;
    }

    public UserTestProfile setUserId(UUID userId) {
        this.userId = userId;
        return this;
    }

    public TestProfile getTestProfile() {
        return testProfile;
    }

    public UserTestProfile setTestProfile(TestProfile testProfile) {
        this.testProfile = testProfile;
        return this;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public UserTestProfile setEnabled(boolean enabled) {
        this.enabled = enabled;
        return this;
    }
}
