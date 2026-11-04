package edu.harvard.hms.dbmi.avillach.conventions;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ValueStringTest {

    private static void assertKeys(String value, String... keys) {
        ValueString parsed = ValueString.parse(value);
        assertEquals(List.of(), parsed.problems(), value);
        assertEquals(List.of(keys), parsed.keys(), value);
    }

    private static void assertProblem(String value, String fragment) {
        List<String> problems = ValueString.parse(value).problems();
        assertTrue(problems.stream().anyMatch(p -> p.contains(fragment)), fragment + " in " + problems + " for " + value);
    }

    @Test
    void readsKeysFromPlaceholdersDefaultsAndExpressions() {
        assertKeys("${a.b}", "a.b");
        assertKeys("${DEST_IP:#{null}}", "DEST_IP");
        assertKeys("${outer:${inner:x}}", "outer", "inner");
        assertKeys("${captcha.turnstile.url:https://challenges.cloudflare.com/turnstile/v0/siteverify}", "captcha.turnstile.url");
        assertKeys("${data-export.s3.bucket-name:}", "data-export.s3.bucket-name");
        assertKeys("#{'${metadata.no_show_list}'}", "metadata.no_show_list");
        assertKeys("#{${dashboard.columns}}", "dashboard.columns");
        assertKeys("#{{'a': 1}}");
        assertKeys("${list[0]}", "list[0]");
        assertKeys("${a}-${b}", "a", "b");
        assertKeys("plain text");
    }

    @Test
    void reportsEachShapeDefect() {
        assertProblem("", "is blank");
        assertProblem("${a", "opens a placeholder at offset 0 that is never closed");
        assertProblem("#{1 + 1", "opens an expression at offset 0 that is never closed");
        assertProblem("${a}}", "has a closing brace at offset 4 that closes nothing");
        assertProblem("${}", "with no key");
        assertProblem("${:x}", "with no key");
        assertProblem("#{ }", "has an empty expression");
        assertProblem("${a b}", "has the key 'a b'");
        assertProblem("${${a}}", "builds the key '${a}'");
        assertProblem("${a:${b}", "never closed");
    }
}
