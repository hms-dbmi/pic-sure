package edu.harvard.hms.dbmi.avillach.operations.banner;

import static edu.harvard.hms.dbmi.avillach.operations.banner.BannerVersionTestSupport.versionsFor;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import edu.harvard.dbmi.avillach.logging.LoggingClient;
import edu.harvard.hms.dbmi.avillach.commons.identity.GatewayUserResolver;

/** Pins the public-feed and management contracts for browser-side audience filtering. */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class BannerAudienceTargetingTest {

    private static final Instant NOW = Instant.parse("2026-08-27T12:00:00Z");
    private static final List<String> PUBLIC_FEED_FIELDS = List.of(
        "uuid", "htmlContent", "title", "appearance", "icon", "dismissible", "audience", "placement", "pageTargets", "priority",
        "presentationHash"
    );

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private BannerRepository repository;

    @Autowired
    private BannerVersionRepository versionRepository;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private LoggingClient loggingClient;

    @ParameterizedTest
    @EnumSource(BannerAudience.class)
    void anonymousFeedCarriesEachAudienceWithoutAdminProvenance(BannerAudience audience) throws Exception {
        UUID uuid = publish(audience);

        JsonNode feed = anonymousFeed();

        assertThat(feed.size()).isOne();
        JsonNode published = feed.get(0);
        assertThat(published.get("uuid").asText()).isEqualTo(uuid.toString());
        assertThat(published.get("audience").asText()).isEqualTo(audience.name());
        assertThat(fieldNames(published)).containsExactlyInAnyOrderElementsOf(PUBLIC_FEED_FIELDS);
    }

    @ParameterizedTest
    @EnumSource(BannerAudience.class)
    void managementContractPersistsAndReturnsEachAudienceExplicitly(BannerAudience audience) throws Exception {
        UUID uuid = publish(audience);

        assertThat(repository.findById(uuid).orElseThrow().getAudience()).isEqualTo(audience);
        mockMvc
            .perform(
                get("/banners").header(GatewayUserResolver.HEADER_USER_ID, "admin-id")
                    .header(GatewayUserResolver.HEADER_USER_PRIVILEGES, "ADMIN")
            ).andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(1))).andExpect(jsonPath("$[0].uuid").value(uuid.toString()))
            .andExpect(jsonPath("$[0].audience").value(audience.name()));
    }

    @Test
    void audienceTargetedBannersRemainObtainableByAnonymousReaders() throws Exception {
        for (BannerAudience audience : BannerAudience.values()) {
            publish(audience);
        }

        List<String> delivered = new ArrayList<>();
        anonymousFeed().forEach(published -> delivered.add(published.get("audience").asText()));

        assertThat(delivered).containsExactlyInAnyOrder("EVERYONE", "SIGNED_IN", "SIGNED_OUT");
    }

    @Test
    void eachAudienceProducesADistinctPresentationHashForOtherwiseIdenticalContent() throws Exception {
        List<String> hashes = new ArrayList<>();

        for (BannerAudience audience : BannerAudience.values()) {
            hashes.add(repository.findById(publish(audience)).orElseThrow().getPresentationHash());
        }

        assertThat(hashes).hasSize(BannerAudience.values().length).doesNotHaveDuplicates();
    }

    @Test
    void audienceEditIsMaterialAndSnapshotsTheNewAudienceAsAnImmutableVersion() throws Exception {
        UUID uuid = publish(BannerAudience.EVERYONE);
        String publishedHash = repository.findById(uuid).orElseThrow().getPresentationHash();

        mockMvc.perform(
            put("/banners/{uuid}", uuid).header(GatewayUserResolver.HEADER_USER_ID, "second-admin-id")
                .header(GatewayUserResolver.HEADER_USER_PRIVILEGES, "ADMIN").contentType(MediaType.APPLICATION_JSON)
                .content(publishRequest(BannerAudience.SIGNED_IN))
        ).andExpect(status().isOk()).andExpect(jsonPath("$.audience").value("SIGNED_IN"));

        String editedHash = repository.findById(uuid).orElseThrow().getPresentationHash();
        assertThat(editedHash).isNotEqualTo(publishedHash);
        assertThat(versionsFor(versionRepository, uuid)).satisfiesExactly(first -> {
            assertThat(first.getVersionNumber()).isOne();
            assertThat(first.getAudience()).isEqualTo(BannerAudience.EVERYONE);
            assertThat(first.getPresentationHash()).isEqualTo(publishedHash);
        }, second -> {
            assertThat(second.getVersionNumber()).isEqualTo(2);
            assertThat(second.getAudience()).isEqualTo(BannerAudience.SIGNED_IN);
            assertThat(second.getPresentationHash()).isEqualTo(editedHash);
        });
        mockMvc.perform(get("/banners/active")).andExpect(status().isOk()).andExpect(jsonPath("$[0].audience").value("SIGNED_IN"))
            .andExpect(jsonPath("$[0].presentationHash").value(editedHash));
    }

    @Test
    void audienceValuesOutsideTheContractAreRejected() throws Exception {
        for (String rejected : List.of("\"SIGNED_IN_ADMINS\"", "\"signed_in\"", "null")) {
            ObjectNode request = (ObjectNode) objectMapper.readTree(publishRequest(BannerAudience.EVERYONE));
            request.set("audience", objectMapper.readTree(rejected));

            mockMvc.perform(adminPost(request.toString())).andExpect(status().isBadRequest());
        }

        assertThat(repository.count()).isZero();
    }

    private UUID publish(BannerAudience audience) throws Exception {
        String response = mockMvc.perform(adminPost(publishRequest(audience))).andExpect(status().isCreated())
            .andExpect(jsonPath("$.audience").value(audience.name())).andReturn().getResponse().getContentAsString();
        return UUID.fromString(objectMapper.readTree(response).get("uuid").asText());
    }

    private JsonNode anonymousFeed() throws Exception {
        return objectMapper
            .readTree(mockMvc.perform(get("/banners/active")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private MockHttpServletRequestBuilder adminPost(String content) {
        return post("/banners").header(GatewayUserResolver.HEADER_USER_ID, "admin-id")
            .header(GatewayUserResolver.HEADER_USER_PRIVILEGES, "ADMIN").contentType(MediaType.APPLICATION_JSON).content(content);
    }

    private String publishRequest(BannerAudience audience) throws Exception {
        return objectMapper.writeValueAsString(
            Map.of(
                "htmlContent", "<p>Audience notice</p>", "title", "Audience notice", "appearance", "PRIMARY", "icon", "INFORMATION",
                "dismissible", true, "audience", audience.name(), "placement", "SITE_TOP", "pageTargets", List.of(Map.of("kind", "ALL"))
            )
        );
    }

    private static List<String> fieldNames(JsonNode node) {
        return node.properties().stream().map(Map.Entry::getKey).toList();
    }

    @TestConfiguration
    static class FixedClockConfiguration {

        @Bean
        @Primary
        @Qualifier("bannerClock")
        Clock fixedClock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }
    }
}
