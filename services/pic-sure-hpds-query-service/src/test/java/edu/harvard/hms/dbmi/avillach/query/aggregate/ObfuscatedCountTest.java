package edu.harvard.hms.dbmi.avillach.query.aggregate;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ObfuscatedCountTest {

    @Test
    void carriesFieldsAndValueEquality() {
        ObfuscatedCount a = new ObfuscatedCount(0, "< 10", 9);
        ObfuscatedCount b = new ObfuscatedCount(0, "< 10", 9);
        assertThat(a).isEqualTo(b);
        assertThat(a.display()).isEqualTo("< 10");
        assertThat(a.count()).isZero();
        assertThat(a.variance()).isEqualTo(9);
    }

    @Test
    void ofIntHasNullVariance() {
        ObfuscatedCount c = ObfuscatedCount.ofInt(42);
        assertThat(c.display()).isEqualTo("42");
        assertThat(c.variance()).isNull();
    }

    @Test
    void serializesCountDisplayVariance() throws Exception {
        String json = new ObjectMapper().writeValueAsString(new ObfuscatedCount(13, "13 ±3", 3));
        assertThat(json).contains("\"count\":13").contains("\"display\":\"13 ±3\"").contains("\"variance\":3");
    }
}
