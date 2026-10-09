package edu.harvard.hms.dbmi.avillach.hpds.processing.v3.layout;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StoreFormatGuardTest {

    @Test
    void acceptsTheKnownFormatsAndTheDefault() {
        assertDoesNotThrow(() -> StoreFormatGuard.check(null));
        assertDoesNotThrow(() -> StoreFormatGuard.check("javabin"));
        assertDoesNotThrow(() -> StoreFormatGuard.check("layout"));
        assertDoesNotThrow(() -> StoreFormatGuard.check("LAYOUT"));
    }

    @Test
    void refusesAnUnknownFormatNamingTheChoices() {
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> StoreFormatGuard.check("layuot"));
        assertTrue(e.getMessage().contains("HPDS_STORE_FORMAT=layuot"), e.getMessage());
        assertTrue(e.getMessage().contains("javabin") && e.getMessage().contains("layout"), e.getMessage());
    }
}
