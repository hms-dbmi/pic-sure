package edu.harvard.hms.dbmi.avillach.conventions;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigurationRulesTest {

    private static final JavaClasses FIXTURES =
        new ClassFileImporter().importPackages("edu.harvard.hms.dbmi.avillach.conventions.configurationfixtures");

    private static final Set<String> FIXTURE_KEYS = Set.of(
        "closed.field", "outer", "inner", "first", "closed.default", "closed.constructor", "closed.method", "closed.extra"
    );

    private static void assertMentions(List<String> violations, String fragment) {
        assertTrue(violations.stream().anyMatch(v -> v.contains(fragment)), fragment + " in " + violations);
    }

    @Test
    void flagsMalformedStringsOnFieldsMethodsConstructorsAndParameters() {
        List<String> violations = ConfigurationRules.valueStringsAreWellFormed("fixtures", FIXTURES);

        assertEquals(8, violations.size(), violations.toString());
        assertMentions(violations, "PlaceholderSettings#openField @Value(\"${open.field\") opens a placeholder at offset 0 that is never closed");
        assertMentions(violations, "PlaceholderSettings#secondOpen @Value(\"${first}-${second\") opens a placeholder at offset 9");
        assertMentions(violations, "PlaceholderSettings#openInsideExpression @Value(\"#{'${open.expression'}\") opens an expression");
        assertMentions(violations, "PlaceholderSettings#<init> parameter 1 @Value(\"${open.constructor\")");
        assertMentions(violations, "PlaceholderSettings#configure parameter 1 @Value(\"${open.method\")");
        assertMentions(violations, "PlaceholderSettings#spacedKey @Value(\"${spaced key}\") has the key 'spaced key'");
        assertMentions(violations, "PlaceholderSettings#strayClose @Value(\"${closed.extra}}\") has a closing brace at offset 15 that closes nothing");
        assertMentions(violations, "PlaceholderSettings#setSetterTarget @Value(\"${:no.key}\") has a placeholder at offset 0 with no key");
        assertTrue(violations.get(0).startsWith("fixtures :: "), violations.get(0));
    }

    @Test
    void acceptsClosedNestedExpressionAndLiteralValues() {
        List<String> violations = ConfigurationRules.valueStringsAreWellFormed("fixtures", FIXTURES);

        for (String accepted : List.of("closedField", "nestedDefault", "literal", "configureClosed", "closedDefault", "closed.constructor")) {
            assertTrue(violations.stream().noneMatch(v -> v.contains(accepted)), accepted + " in " + violations);
        }
    }

    @Test
    void flagsEachUndeclaredKeyAtEachSite() {
        Set<String> declaredWithoutInner = Set.of("closed.field", "outer", "first", "closed.default", "closed.constructor", "closed.method", "closed.extra");

        List<String> violations = ConfigurationRules.valueKeysAreDeclared("fixtures", FIXTURES, declaredWithoutInner);

        assertEquals(1, violations.size(), violations.toString());
        assertMentions(violations, "PlaceholderSettings#nestedDefault @Value(\"${outer:${inner}}\") reads 'inner', which " + PropertyMetadata.ADDITIONAL + " does not declare");
    }

    @Test
    void acceptsKeysTheMetadataDeclares() {
        assertEquals(List.of(), ConfigurationRules.valueKeysAreDeclared("fixtures", FIXTURES, FIXTURE_KEYS));
    }

    @Test
    void readsDeclaredNamesFromBothMetadataFiles(@TempDir Path classes) throws IOException {
        write(classes, PropertyMetadata.ADDITIONAL, """
            {"properties": [{"name": "hand.written", "type": "java.lang.String", "description": "Written by hand."}]}
            """);
        write(classes, PropertyMetadata.GENERATED, """
            {"properties": [{"name": "generated.only", "type": "java.lang.String"}]}
            """);

        PropertyMetadata metadata = PropertyMetadata.load(classes);

        assertEquals(Set.of("hand.written", "generated.only"), metadata.declared());
        assertEquals(List.of(), metadata.problems());
    }

    @Test
    void flagsIncompleteAndDuplicateEntries(@TempDir Path classes) throws IOException {
        write(classes, PropertyMetadata.ADDITIONAL, """
            {"properties": [
              {"name": "twice", "type": "java.lang.String", "description": "First."},
              {"name": "twice", "type": "java.lang.String", "description": "Second."},
              {"name": "untyped", "description": "No type."},
              {"name": "undescribed", "type": "java.lang.String", "description": " "},
              {"type": "java.lang.String", "description": "No name."}
            ]}
            """);

        List<String> violations = ConfigurationRules.metadataIsComplete("fixtures", PropertyMetadata.load(classes));

        assertEquals(4, violations.size(), violations.toString());
        assertMentions(violations, "fixtures :: " + PropertyMetadata.ADDITIONAL + " declares 'twice' more than once");
        assertMentions(violations, "gives 'untyped' no type");
        assertMentions(violations, "gives 'undescribed' no description");
        assertMentions(violations, "has property 4 with no name");
    }

    @Test
    void flagsUnreadableMetadata(@TempDir Path classes) throws IOException {
        write(classes, PropertyMetadata.ADDITIONAL, "{\"properties\": [");

        assertMentions(PropertyMetadata.load(classes).problems(), "is not valid JSON");
    }

    @Test
    void treatsAbsentMetadataAsDeclaringNothing(@TempDir Path classes) {
        PropertyMetadata metadata = PropertyMetadata.load(classes);

        assertEquals(Set.of(), metadata.declared());
        assertEquals(List.of(), metadata.problems());
    }

    private static void write(Path classes, String relative, String content) throws IOException {
        Path file = classes.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }
}
