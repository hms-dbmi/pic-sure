package edu.harvard.hms.dbmi.avillach.auth.model.request;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Pins which request members reject angle brackets. Each marked member refuses markup and accepts ordinary text and null, and the members
 * that carry syntax stay unrestricted.
 */
class PlainTextRequestTest {

    private static final String MARKUP = "<img src=x onerror=alert(1)>";

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    private static AccessRuleCreateRequest accessRule(String name, String description, String rule, String value) {
        return new AccessRuleCreateRequest(name, description, 4, rule, value, null, null, null, null, null, null);
    }

    private static Set<String> rejectedMembers(Set<? extends ConstraintViolation<?>> violations) {
        return violations.stream().map(v -> v.getPropertyPath().toString()).collect(java.util.stream.Collectors.toSet());
    }

    @Test
    void accessRuleCreateRejectsMarkupInNameAndDescription() {
        assertThat(rejectedMembers(validator.validate(accessRule(MARKUP, MARKUP, "$.path", "/search/"))))
            .containsExactlyInAnyOrder("name", "description");
    }

    @Test
    void accessRuleCreateAcceptsPlainTextAndLeavesRuleSyntaxAlone() {
        assertThat(validator.validate(accessRule("AR_ONLY_SEARCH", null, "$[?(@.count > 5)]", "a<b"))).isEmpty();
    }

    @Test
    void accessRuleUpdateRejectsMarkupInNameAndDescription() {
        AccessRuleUpdateRequest request =
            new AccessRuleUpdateRequest(UUID.randomUUID(), MARKUP, MARKUP, null, null, null, null, null, null, null, null, null);

        assertThat(rejectedMembers(validator.validate(request))).containsExactlyInAnyOrder("name", "description");
    }

    @Test
    void accessRuleUpdateAcceptsAbsentMembers() {
        AccessRuleUpdateRequest request =
            new AccessRuleUpdateRequest(UUID.randomUUID(), null, null, null, null, null, null, null, null, null, null, null);

        assertThat(validator.validate(request)).isEmpty();
    }

    @Test
    void applicationCreateRejectsMarkupInNameDescriptionAndUrl() {
        ApplicationCreateRequest request = new ApplicationCreateRequest(MARKUP, MARKUP, MARKUP, null, null);

        assertThat(rejectedMembers(validator.validate(request))).containsExactlyInAnyOrder("name", "description", "url");
    }

    @Test
    void applicationCreateAcceptsPlainText() {
        assertThat(validator.validate(new ApplicationCreateRequest("PICSURE", "The PIC-SURE API application", "/picsureui", null, null)))
            .isEmpty();
    }

    @Test
    void applicationUpdateRejectsMarkupInNameDescriptionAndUrl() {
        ApplicationUpdateRequest request = new ApplicationUpdateRequest(UUID.randomUUID(), MARKUP, MARKUP, MARKUP, null, null);

        assertThat(rejectedMembers(validator.validate(request))).containsExactlyInAnyOrder("name", "description", "url");
    }

    @Test
    void connectionCreateRejectsMarkupButNotTheRequiredFieldsDocument() {
        ConnectionCreateRequest request = new ConnectionCreateRequest(MARKUP, MARKUP, MARKUP, "[{\"label\":\"a<b\",\"id\":\"email\"}]");

        assertThat(rejectedMembers(validator.validate(request))).containsExactlyInAnyOrder("id", "label", "subPrefix");
    }

    @Test
    void connectionUpdateRejectsMarkupInIdLabelAndSubPrefix() {
        ConnectionUpdateRequest request = new ConnectionUpdateRequest(UUID.randomUUID(), MARKUP, MARKUP, MARKUP, null);

        assertThat(rejectedMembers(validator.validate(request))).containsExactlyInAnyOrder("id", "label", "subPrefix");
    }

    @Test
    void userCreateRejectsMarkupInEmail() {
        UserCreateRequest request = new UserCreateRequest(MARKUP, null, MARKUP, null, Set.of(new EntityIdRef(UUID.randomUUID())));

        assertThat(rejectedMembers(validator.validate(request))).containsExactlyInAnyOrder("email");
    }

    @Test
    void userCreateAcceptsOrdinaryMetadata() {
        UserCreateRequest request = new UserCreateRequest(
            "researcher@example.org", null, "{\"email\":\"researcher@example.org\"}", null, Set.of(new EntityIdRef(UUID.randomUUID()))
        );

        assertThat(validator.validate(request)).isEmpty();
    }

    @Test
    void userUpdateRejectsMarkupInEmail() {
        UserUpdateRequest request = new UserUpdateRequest(UUID.randomUUID(), MARKUP, null, MARKUP, null, null);

        assertThat(rejectedMembers(validator.validate(request))).containsExactlyInAnyOrder("email");
    }
}
