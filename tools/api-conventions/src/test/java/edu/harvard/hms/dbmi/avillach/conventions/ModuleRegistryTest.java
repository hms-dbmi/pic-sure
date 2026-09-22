package edu.harvard.hms.dbmi.avillach.conventions;

import java.io.StringReader;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModuleRegistryTest {

    @Test
    void readsBothScopes() {
        ModuleRegistry registry = ModuleRegistry.parse(new StringReader("""
            services/a = documented
            services/b = internal: not a client API
            """));

        assertEquals(Optional.of(ModuleScope.DOCUMENTED), registry.scopeOf("services/a"));
        assertEquals(Optional.of(ModuleScope.INTERNAL), registry.scopeOf("services/b"));
        assertEquals(Set.of("services/a"), registry.documentedModules());
        assertTrue(registry.contains("services/b"));
        assertFalse(registry.contains("services/c"));
        assertEquals(Optional.empty(), registry.scopeOf("services/c"));
    }

    @Test
    void rejectsInternalWithoutAReason() {
        IllegalArgumentException thrown = assertThrows(
            IllegalArgumentException.class,
            () -> ModuleRegistry.parse(new StringReader("services/b = internal\n"))
        );
        assertTrue(thrown.getMessage().contains("services/b"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("reason"), thrown.getMessage());
    }

    @Test
    void rejectsAnUnknownScope() {
        IllegalArgumentException thrown = assertThrows(
            IllegalArgumentException.class,
            () -> ModuleRegistry.parse(new StringReader("services/b = maybe\n"))
        );
        assertTrue(thrown.getMessage().contains("maybe"), thrown.getMessage());
    }

    @Test
    void rejectsADuplicateEntry() {
        assertThrows(
            IllegalArgumentException.class,
            () -> ModuleRegistry.parse(new StringReader("services/a = documented\nservices/a = internal: x\n"))
        );
    }

    @Test
    void theRealRegistryClassifiesEveryKnownModule() {
        ModuleRegistry registry = ModuleRegistry.load();

        assertEquals(Optional.of(ModuleScope.DOCUMENTED), registry.scopeOf("services/pic-sure-auth-microapp/pic-sure-auth-services"));
        assertEquals(Optional.of(ModuleScope.DOCUMENTED), registry.scopeOf("services/picsure-dictionary"));
        assertEquals(Optional.of(ModuleScope.INTERNAL), registry.scopeOf("services/pic-sure-hpds/service"));
        assertEquals(Optional.of(ModuleScope.INTERNAL), registry.scopeOf("services/picsure-dictionary/aggregate"));
        assertEquals(5, registry.documentedModules().size());
    }
}
