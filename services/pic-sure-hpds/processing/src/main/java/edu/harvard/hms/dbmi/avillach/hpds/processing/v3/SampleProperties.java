package edu.harvard.hms.dbmi.avillach.hpds.processing.v3;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.HexFormat;

/**
 * Settings for sample exports, bound from the {@code sample} prefix.
 *
 * <p>{@code sample.min-size} is the least number of participants in a sample. A cohort of that size or smaller is exported whole. It
 * defaults to 100, a blank value means the default, and a value below 1 is refused when the properties are bound.
 *
 * <p>{@code sample.salt} decides which participants a sample holds. The same salt gives the same sample for the same query and cohort,
 * across restarts. A blank salt is replaced once, when this object is built, by a random value, so samples are stable within one process
 * and change on restart.
 */
@Component
@ConfigurationProperties(prefix = "sample")
public class SampleProperties {

    static final int DEFAULT_MIN_SIZE = 100;
    private static final int RANDOM_SALT_BYTES = 32;

    private int minSize = DEFAULT_MIN_SIZE;
    private String salt = randomSalt();

    /**
     * @return the least number of participants in a sample, always 1 or more
     */
    public Integer getMinSize() {
        return minSize;
    }

    /**
     * Sets the least number of participants in a sample.
     *
     * @param minSize the size, or null to keep the default of 100
     * @throws IllegalArgumentException when the size is below 1
     */
    public void setMinSize(Integer minSize) {
        if (minSize == null) {
            this.minSize = DEFAULT_MIN_SIZE;
            return;
        }
        if (minSize < 1) {
            throw new IllegalArgumentException("sample.min-size (SAMPLE_MIN_SIZE) must be 1 or more, but was " + minSize);
        }
        this.minSize = minSize;
    }

    /**
     * @return the salt, never blank
     */
    public String getSalt() {
        return salt;
    }

    /**
     * Sets the salt. A null or blank value is ignored, which keeps the random salt chosen at construction.
     *
     * @param salt the configured salt
     */
    public void setSalt(String salt) {
        if (salt != null && !salt.isBlank()) {
            this.salt = salt;
        }
    }

    private static String randomSalt() {
        byte[] bytes = new byte[RANDOM_SALT_BYTES];
        new SecureRandom().nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }
}
