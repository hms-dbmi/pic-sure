package edu.harvard.dbmi.avillach.logging.config;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtClaimMappingConverterTest {

    private final JwtClaimMappingConverter converter = new JwtClaimMappingConverter();

    @Test
    void blankSourceYieldsDefaultMapping() {
        Map<String, String> mapping = converter.convert("");

        assertThat(mapping).isEqualTo(JwtClaimMappingConverter.DEFAULT_MAPPING);
        assertThat(mapping).containsEntry("sub", "subject");
        assertThat(mapping).containsEntry("userid", "user_id");
        assertThat(mapping).containsEntry("preferred_username", "preferred_username");
        assertThat(mapping).containsEntry("user_permission_group", "user_permission_group");
        assertThat(mapping).containsEntry("idp", "user_id_provider");
        // The historic default has no session_id and no logged_in entry.
        assertThat(mapping).doesNotContainKey("session_id");
        assertThat(mapping).doesNotContainKey("logged_in");
    }

    @Test
    void defaultMappingPreservesDocumentedDeclarationOrder() {
        assertThat(JwtClaimMappingConverter.DEFAULT_MAPPING.keySet()).containsExactly(
            "sub", "email", "name", "userid", "preferred_username", "org", "country_name", "nih_ico", "eRA_commons_id",
            "user_permission_group", "uuid", "roles", "idp", "cadr_name"
        );
    }

    @Test
    void validJsonIsParsed() {
        assertThat(converter.convert("{\"custom_claim\":\"output_field\"}")).isEqualTo(Map.of("custom_claim", "output_field"));
    }

    @Test
    void emptyJsonObjectYieldsAnEmptyMap() {
        Map<String, String> mapping = converter.convert("{}");

        assertThat(mapping).isEmpty();
        assertThat(mapping).isNotEqualTo(JwtClaimMappingConverter.DEFAULT_MAPPING);
    }

    @Test
    void invalidJsonFailsFastWithTheVariableName() {
        assertThatThrownBy(() -> converter.convert("not-json")).isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("JWT_CLAIM_MAPPING");
    }

    @Test
    void nullJsonValueFailsFastWithTheVariableName() {
        assertThatThrownBy(() -> converter.convert("{\"sub\":null}")).isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("JWT_CLAIM_MAPPING").hasMessageContaining("null");
    }
}
