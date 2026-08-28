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

    private static final String TARGETED_FEED_BOUNDARY =
        "DISABLE_ACTIVE_AND_SCHEDULED_TARGETED_BANNERS_BEFORE_OPERATIONS_OR_GATEWAY_BELOW_TICKET_08";

    @Test
    void definesTheSharedForwardAndRollbackOrder() throws IOException {
        JsonNode contract = new ObjectMapper().readTree(contractPath().toFile());

        assertThat(contract.path("schemaVersion").asInt()).isEqualTo(1);
        assertThat(contract.path("cacheRefresh").asText()).isEqualTo("PSAMA_PROCESS_RESTART");
        assertThat(textValues(contract.path("forwardPhases"))).containsExactly(
            "APPLY_AUTHORIZATION_AND_PIC_SURE_MIGRATIONS", "RECREATE_PSAMA", "VERIFY_OPERATIONS_AND_GATEWAY_HEALTH",
            "PUBLISH_FRONTEND_ACTIVE_V2"
        );
        assertThat(textValues(contract.path("rollbackPhases")))
            .containsExactly("ROLL_BACK_FRONTEND", TARGETED_FEED_BOUNDARY, "ROLL_BACK_OPERATIONS_AND_GATEWAY", "RECREATE_PSAMA");
        assertThat(contract.path("targetedFeedRollbackBoundary").asText()).isEqualTo(TARGETED_FEED_BOUNDARY);
        assertThat(contract.path("schemaRollback").asText()).isEqualTo("KEEP_FORWARD_SCHEMA");
        assertThat(contract.path("downMigrationAllowed").asBoolean()).isFalse();
    }

    private List<String> textValues(JsonNode array) {
        return StreamSupport.stream(array.spliterator(), false).map(JsonNode::asText).toList();
    }

    private Path contractPath() {
        Path directory = Path.of(System.getProperty("maven.multiModuleProjectDirectory", ".")).toAbsolutePath();
        while (directory != null) {
            Path contract = directory.resolve(".github/banner-rollout-contract.json");
            if (Files.isRegularFile(contract)) {
                return contract;
            }
            directory = directory.getParent();
        }
        throw new IllegalStateException("Could not find .github/banner-rollout-contract.json");
    }
}
