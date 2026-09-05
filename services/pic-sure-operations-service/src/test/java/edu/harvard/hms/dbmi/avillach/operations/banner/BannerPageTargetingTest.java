package edu.harvard.hms.dbmi.avillach.operations.banner;

import static edu.harvard.hms.dbmi.avillach.operations.banner.BannerVersionTestSupport.versionsFor;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeout;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import edu.harvard.dbmi.avillach.logging.LoggingClient;
import edu.harvard.hms.dbmi.avillach.commons.identity.GatewayUserResolver;
import jakarta.persistence.EntityManager;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class BannerPageTargetingTest {

    private static final Instant NOW = Instant.parse("2026-08-28T12:00:00Z");

    @Autowired
    private BannerPriorityAllocatorRepository allocatorRepository;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private BannerRepository repository;

    @Autowired
    private BannerVersionRepository versionRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private EntityManager entityManager;

    @MockitoBean
    private LoggingClient loggingClient;

    @BeforeEach
    void seedPriorityAllocator() {
        allocatorRepository.saveAndFlush(
            new BannerPriorityAllocator().setId(BannerPriorityAllocator.SINGLETON_ID).setNextPriority(1)
        );
    }

    @Test
    void normalizesTargetsForStorageManagementFeedAndPublishedVersion() throws Exception {
        JsonNode submitted = objectMapper.readTree("""
            [
              {"kind":"SUBTREE","path":"/admin/"},
              {"kind":"EXACT","path":"/help /"},
              {"kind":"EXACT","path":" /removed-route/ "},
              {"kind":"PARAMETERIZED","path":"/studies/[study]/participants/[participant]/"},
              {"kind":"EXACT","path":"/removed-route"}
            ]
            """);

        JsonNode published = publish(submitted);
        UUID uuid = UUID.fromString(published.get("uuid").asText());
        JsonNode expected = objectMapper.readTree("""
            [
              {"kind":"EXACT","path":"/help"},
              {"kind":"EXACT","path":"/removed-route"},
              {"kind":"PARAMETERIZED","path":"/studies/[study]/participants/[participant]"},
              {"kind":"SUBTREE","path":"/admin"}
            ]
            """);

        assertThat(published.get("pageTargets")).isEqualTo(expected);
        JsonNode storedTargets = objectMapper.valueToTree(repository.findById(uuid).orElseThrow().getPageTargets());
        JsonNode versionTargets = objectMapper.valueToTree(versionsFor(versionRepository, uuid).getFirst().getPageTargets());
        assertThat(storedTargets).isEqualTo(expected);
        assertThat(versionTargets).isEqualTo(expected);
        mockMvc.perform(get("/banners").headers(adminHeaders())).andExpect(status().isOk())
            .andExpect(jsonPath("$[0].pageTargets[0].kind").value("EXACT"))
            .andExpect(jsonPath("$[0].pageTargets[0].path").value("/help"));
        mockMvc.perform(get("/banners/active")).andExpect(status().isOk())
            .andExpect(jsonPath("$[0].pageTargets[2].kind").value("PARAMETERIZED"))
            .andExpect(jsonPath("$[0].pageTargets[3].path").value("/admin")).andExpect(jsonPath("$[0].createdBy").doesNotExist());
    }

    @Test
    void activeFeedIncludesAllPagesAndTargetedBanners() throws Exception {
        JsonNode targeted = publish(objectMapper.readTree("[{\"kind\":\"EXACT\",\"path\":\"/help\"}]"));
        publish(objectMapper.readTree("[{\"kind\":\"ALL\"}]"));

        mockMvc.perform(get("/banners/active")).andExpect(status().isOk())
            .andExpect(jsonPath("$", org.hamcrest.Matchers.hasSize(2)))
            .andExpect(jsonPath("$[0].uuid").value(targeted.get("uuid").asText()))
            .andExpect(jsonPath("$[0].htmlContent").value("<p>Page-targeted notice</p>"))
            .andExpect(jsonPath("$[0].title").value("Page target"))
            .andExpect(jsonPath("$[0].appearance").value("PRIMARY"))
            .andExpect(jsonPath("$[0].icon").value("INFORMATION"))
            .andExpect(jsonPath("$[0].dismissible").value(true))
            .andExpect(jsonPath("$[0].audience").value("EVERYONE"))
            .andExpect(jsonPath("$[0].placement").value("SITE_TOP"))
            .andExpect(jsonPath("$[0].pageTargets[0].kind").value("EXACT"))
            .andExpect(jsonPath("$[0].pageTargets[0].path").value("/help"))
            .andExpect(jsonPath("$[0].priority").isNumber())
            .andExpect(jsonPath("$[0].presentationHash").value(targeted.get("presentationHash").asText()))
            .andExpect(jsonPath("$[0].createdBy").doesNotExist())
            .andExpect(jsonPath("$[1].pageTargets[0].kind").value("ALL"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"[]", "[{\"kind\":\"FUTURE\",\"path\":\"/hidden\"}]"})
    void malformedStoredTargetsAreOmittedWithoutTakingAnyFeedDown(String storedTargets) throws Exception {
        JsonNode malformed = publish(objectMapper.readTree("[{\"kind\":\"EXACT\",\"path\":\"/hidden\"}]"));
        publish(objectMapper.readTree("[{\"kind\":\"ALL\"}]"));
        UUID malformedUuid = UUID.fromString(malformed.get("uuid").asText());
        jdbcTemplate.update(
            "UPDATE banner_occurrence SET page_targets = CAST(? AS JSON) WHERE uuid = ?",
            storedTargets, malformedUuid
        );
        entityManager.clear();

        mockMvc.perform(get("/banners").headers(adminHeaders())).andExpect(status().isOk())
            .andExpect(jsonPath("$", org.hamcrest.Matchers.hasSize(1)));
        mockMvc.perform(get("/banners/active")).andExpect(status().isOk())
            .andExpect(jsonPath("$", org.hamcrest.Matchers.hasSize(1)));
    }

    @Test
    void allPagesHasOneCanonicalShape() throws Exception {
        JsonNode published = publish(objectMapper.readTree("[{\"kind\":\"ALL\"}]"));

        assertThat(published.get("pageTargets")).isEqualTo(objectMapper.readTree("[{\"kind\":\"ALL\"}]"));
        assertThat(published.get("pageTargets").get(0).has("path")).isFalse();
    }

    @Test
    void acceptsTheRootAsAnExactPage() throws Exception {
        JsonNode published = publish(objectMapper.readTree("[{\"kind\":\"EXACT\",\"path\":\"/\"}]"));

        assertThat(published.get("pageTargets")).isEqualTo(objectMapper.readTree("[{\"kind\":\"EXACT\",\"path\":\"/\"}]"));
    }

    @Test
    void normalizationIsIdempotentWhenSpacesTouchRemovableTrailingSlashes() {
        List<BannerPageTarget> once = BannerPageTargets.normalize(
            List.of(new BannerPageTarget(BannerPageTargetKind.EXACT, "/help /"))
        );

        assertThat(once).containsExactly(new BannerPageTarget(BannerPageTargetKind.EXACT, "/help"));
        assertThat(BannerPageTargets.normalize(once)).isEqualTo(once);
    }

    @ParameterizedTest
    @ValueSource(
        strings = {"[]", "[null]", "[{}]", "[{\"kind\":\"ALL\",\"path\":\"/\"}]",
            "[{\"kind\":\"ALL\"},{\"kind\":\"EXACT\",\"path\":\"/help\"}]", "[{\"kind\":\"EXACT\"}]", "[{\"kind\":\"EXACT\",\"path\":7}]",
            "[{\"kind\":\"EXACT\",\"path\":\"/help\",\"extra\":true}]", "[{\"kind\":\"PREFIX\",\"path\":\"/help\"}]",
            "[{\"kind\":\"EXACT\",\"path\":\"help\"}]", "[{\"kind\":\"EXACT\",\"path\":\"\\t/help\"}]",
            "[{\"kind\":\"EXACT\",\"path\":\"/help?topic=banners\"}]", "[{\"kind\":\"EXACT\",\"path\":\"/help#banners\"}]",
            "[{\"kind\":\"EXACT\",\"path\":\"/help//banners\"}]", "[{\"kind\":\"EXACT\",\"path\":\"/help/../admin\"}]",
            "[{\"kind\":\"SUBTREE\",\"path\":\"/\"}]", "[{\"kind\":\"SUBTREE\",\"path\":\"/ /\"}]",
            "[{\"kind\":\"SUBTREE\",\"path\":\"/help/*\"}]",
            "[{\"kind\":\"PARAMETERIZED\",\"path\":\"/studies\"}]", "[{\"kind\":\"PARAMETERIZED\",\"path\":\"/studies/[[study]]\"}]",
            "[{\"kind\":\"PARAMETERIZED\",\"path\":\"/studies/[...study]\"}]",
            "[{\"kind\":\"PARAMETERIZED\",\"path\":\"/studies/[study=uuid]\"}]",
            "[{\"kind\":\"PARAMETERIZED\",\"path\":\"/studies/[study\"}]"}
    )
    void rejectsMalformedOrUnsupportedTargets(String pageTargets) throws Exception {
        mockMvc.perform(adminPost(request(objectMapper.readTree(pageTargets))).contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isBadRequest());

        assertThat(repository.count()).isZero();
    }

    @Test
    void doesNotImposeAFeatureSpecificTargetCountOrApplicationRouteAllowlist() throws Exception {
        List<Map<String, String>> targets = java.util.stream.IntStream.range(0, 150)
            .mapToObj(index -> Map.of("kind", "EXACT", "path", "/deployment-route-" + index)).toList();

        JsonNode published = publish(objectMapper.valueToTree(targets));

        assertThat(published.get("pageTargets")).hasSize(150);
    }

    @Test
    void trimsAnUnboundedTargetPathInLinearTime() {
        String path = "/help" + "/".repeat(200_000);

        List<BannerPageTarget> normalized = assertTimeout(
            Duration.ofMillis(500), () -> BannerPageTargets.normalize(List.of(new BannerPageTarget(BannerPageTargetKind.EXACT, path)))
        );

        assertThat(normalized).containsExactly(new BannerPageTarget(BannerPageTargetKind.EXACT, "/help"));
    }

    @Test
    void normalizedEquivalentEditIsANoOpButChangedTargetsCreateANewVersionAndHash() throws Exception {
        JsonNode published =
            publish(objectMapper.readTree("[{\"kind\":\"SUBTREE\",\"path\":\"/help\"},{\"kind\":\"EXACT\",\"path\":\"/status\"}]"));
        UUID uuid = UUID.fromString(published.get("uuid").asText());
        String originalHash = published.get("presentationHash").asText();

        JsonNode noOpRequest =
            request(objectMapper.readTree("[{\"kind\":\"EXACT\",\"path\":\" /status/ \"},{\"kind\":\"SUBTREE\",\"path\":\"/help/\"}]"));
        JsonNode noOp =
            response(
                mockMvc.perform(
                    put("/banners/{uuid}", uuid).headers(adminHeaders()).contentType(MediaType.APPLICATION_JSON)
                        .content(noOpRequest.toString())
                ).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()
            );

        assertThat(noOp.get("presentationHash").asText()).isEqualTo(originalHash);
        assertThat(versionsFor(versionRepository, uuid)).hasSize(1);

        JsonNode changedRequest = request(objectMapper.readTree("[{\"kind\":\"EXACT\",\"path\":\"/status/incident\"}]"));
        JsonNode changed = response(
            mockMvc.perform(
                put("/banners/{uuid}", uuid).headers(adminHeaders()).contentType(MediaType.APPLICATION_JSON)
                    .content(changedRequest.toString())
            ).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()
        );

        assertThat(changed.get("presentationHash").asText()).isNotEqualTo(originalHash);
        assertThat(versionsFor(versionRepository, uuid)).hasSize(2);
        JsonNode versionTargets = objectMapper.valueToTree(versionsFor(versionRepository, uuid).get(1).getPageTargets());
        assertThat(versionTargets).isEqualTo(objectMapper.readTree("[{\"kind\":\"EXACT\",\"path\":\"/status/incident\"}]"));
    }

    private JsonNode publish(JsonNode pageTargets) throws Exception {
        return response(
            mockMvc.perform(adminPost(request(pageTargets)).contentType(MediaType.APPLICATION_JSON)).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString()
        );
    }

    private ObjectNode request(JsonNode pageTargets) {
        return objectMapper.valueToTree(
            Map.of(
                "htmlContent", "<p>Page-targeted notice</p>", "title", "Page target", "appearance", "PRIMARY", "icon", "INFORMATION",
                "dismissible", true, "audience", "EVERYONE", "placement", "SITE_TOP", "pageTargets", pageTargets
            )
        );
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder adminPost(JsonNode content) {
        return post("/banners").headers(adminHeaders()).content(content.toString());
    }

    private org.springframework.http.HttpHeaders adminHeaders() {
        org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
        headers.set(GatewayUserResolver.HEADER_USER_ID, "admin-id");
        headers.set(GatewayUserResolver.HEADER_USER_PRIVILEGES, "ADMIN");
        return headers;
    }

    private JsonNode response(String content) throws Exception {
        return objectMapper.readTree(content);
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
