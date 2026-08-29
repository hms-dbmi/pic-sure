package edu.harvard.hms.dbmi.avillach.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.StreamSupport;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

class BannerRolloutContractTest {

    private static final String REPOSITORY_ROOT_OVERRIDE = "banner.rollout.repositoryRoot";
    private static final String TARGETED_FEED_BOUNDARY = "DISABLE_ACTIVE_AND_SCHEDULED_TARGETED_BANNERS_BEFORE_LEGACY_ACTIVE_FEED_BACKEND";
    private static final String RETAINED_FREEZE_BOUNDARY = "KEEP_BANNER_MANAGEMENT_WRITES_FROZEN_BELOW_TARGETING_CAPABLE_BACKEND";

    @Test
    void definesTheSharedForwardAndRollbackOrder() throws IOException {
        JsonNode contract = new ObjectMapper().readTree(contractPath().toFile());

        assertThat(contract.path("schemaVersion").asInt()).isEqualTo(3);
        assertThat(contract.path("deploymentWideCacheRefresh").asText()).isEqualTo("PSAMA_PROCESS_RESTART");
        assertThat(textValues(contract.path("forwardPhases"))).containsExactly(
            "APPLY_AUTHORIZATION_AND_PIC_SURE_MIGRATIONS", "RECREATE_PSAMA", "VERIFY_OPERATIONS_AND_GATEWAY_HEALTH",
            "PUBLISH_FRONTEND_ACTIVE_V2"
        );
        assertThat(textValues(contract.path("rollbackPhases")))
            .containsExactly(
                "FREEZE_BANNER_MANAGEMENT_WRITES", "ROLL_BACK_FRONTEND", TARGETED_FEED_BOUNDARY,
                "ROLL_BACK_OPERATIONS_AND_GATEWAY", RETAINED_FREEZE_BOUNDARY, "RECREATE_PSAMA"
            );
        assertThat(contract.path("targetedFeedRollbackBoundary").asText()).isEqualTo(TARGETED_FEED_BOUNDARY);
        assertThat(contract.path("managementWriteFreezeBoundary").asText()).isEqualTo(RETAINED_FREEZE_BOUNDARY);
        JsonNode rollbackState = contract.path("rollbackStateContract");
        assertThat(rollbackState.path("freezeRequiredBeforeFrontendRollback").asBoolean()).isTrue();
        assertThat(rollbackState.path("ordinaryManagementWritesAllowedWhileFrozen").asBoolean()).isFalse();
        assertThat(rollbackState.path("targetedDisableAllowedWhileFrozen").asBoolean()).isTrue();
        assertThat(rollbackState.path("legacyBackendTransitionRequiresTargetedClear").asBoolean()).isTrue();
        assertThat(rollbackState.path("freezeRetainedBelowTargetingBackend").asBoolean()).isTrue();
        assertThat(rollbackState.path("frontendFirstRollbackAloneSafe").asBoolean()).isFalse();
        assertThat(contract.path("schemaRollback").asText()).isEqualTo("KEEP_FORWARD_SCHEMA");
        assertThat(contract.path("downMigrationAllowed").asBoolean()).isFalse();
    }

    private List<String> textValues(JsonNode array) {
        return StreamSupport.stream(array.spliterator(), false).map(JsonNode::asText).toList();
    }

    private Path contractPath() {
        String repositoryRoot = System.getProperty(REPOSITORY_ROOT_OVERRIDE);
        String moduleRoot = System.getProperty("basedir");
        if ((repositoryRoot == null || repositoryRoot.isBlank()) && (moduleRoot == null || moduleRoot.isBlank())) {
            throw new IllegalStateException("Maven module root is unavailable; set -D" + REPOSITORY_ROOT_OVERRIDE + "=<pic-sure checkout>");
        }

        Path root =
            repositoryRoot == null || repositoryRoot.isBlank() ? Path.of(moduleRoot).resolve("../../..").toAbsolutePath().normalize()
                : Path.of(repositoryRoot).toAbsolutePath().normalize();
        Path contract = root.resolve(".github/banner-rollout-contract.json");
        if (!Files.isRegularFile(contract)) {
            throw new IllegalStateException(
                "Missing .github/banner-rollout-contract.json under " + root + "; override with -D" + REPOSITORY_ROOT_OVERRIDE
                    + "=<pic-sure checkout>"
            );
        }
        return contract;
    }
}
