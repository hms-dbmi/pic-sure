package edu.harvard.hms.dbmi.avillach.operations.banner;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
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
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import edu.harvard.dbmi.avillach.logging.LoggingClient;
import edu.harvard.hms.dbmi.avillach.commons.identity.GatewayUserResolver;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class BannerControllerTest {

    private static final Instant NOW = Instant.parse("2026-08-27T12:00:00Z");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private BannerRepository repository;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private LoggingClient loggingClient;

    @Test
    void activeFeedIsAnonymousStartInclusiveEndExclusiveAndPriorityOrdered() throws Exception {
        repository.save(banner(30, BannerStatus.PUBLISHED, NOW.minusSeconds(60), null, "Second"));
        repository.save(banner(10, BannerStatus.PUBLISHED, NOW, NOW.plusSeconds(60), "First"));
        repository.save(banner(1, BannerStatus.SAVED, NOW.minusSeconds(60), null, "Saved"));
        repository.save(banner(2, BannerStatus.DISABLED, NOW.minusSeconds(60), null, "Disabled"));
        repository.save(banner(3, BannerStatus.ARCHIVED, NOW.minusSeconds(60), null, "Archived"));
        repository.save(banner(4, BannerStatus.PUBLISHED, NOW.plusSeconds(1), null, "Scheduled"));
        repository.save(banner(5, BannerStatus.PUBLISHED, NOW.minusSeconds(60), NOW, "Expired"));

        mockMvc.perform(get("/banners/active")).andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(2)))
            .andExpect(jsonPath("$[0].priority").value(10)).andExpect(jsonPath("$[0].title").value("First"))
            .andExpect(jsonPath("$[0].htmlContent").value("<p>First content</p>")).andExpect(jsonPath("$[0].appearance").value("PRIMARY"))
            .andExpect(jsonPath("$[0].icon").value("INFORMATION")).andExpect(jsonPath("$[0].dismissible").value(true))
            .andExpect(jsonPath("$[0].audience").value("EVERYONE")).andExpect(jsonPath("$[0].placement").value("SITE_TOP"))
            .andExpect(jsonPath("$[0].pageTargets[0].kind").value("ALL")).andExpect(jsonPath("$[0].presentationHash").value("hash-First"))
            .andExpect(jsonPath("$[0].createdBy").doesNotExist()).andExpect(jsonPath("$[0].publishedAt").doesNotExist())
            .andExpect(jsonPath("$[1].priority").value(30)).andExpect(jsonPath("$[1].title").value("Second"));
    }

    @Test
    void activeFeedIsEmptyWhenNoBannerIsActive() throws Exception {
        repository.save(banner(1, BannerStatus.PUBLISHED, NOW.plusSeconds(1), null, "Later"));

        mockMvc.perform(get("/banners/active")).andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    void duplicatePrioritiesAreOrderedByUuid() throws Exception {
        List<BannerOccurrence> saved = repository.saveAll(
            List.of(
                banner(10, BannerStatus.PUBLISHED, NOW.minusSeconds(60), null, "Duplicate A"),
                banner(10, BannerStatus.PUBLISHED, NOW.minusSeconds(60), null, "Duplicate B")
            )
        );
        // Canonical UUID text order matches BINARY(16) unsigned byte order; this detects mapping or ordering-semantics changes.
        List<String> expectedIds =
            saved.stream().map(BannerOccurrence::getUuid).sorted(Comparator.comparing(UUID::toString)).map(UUID::toString).toList();

        mockMvc.perform(get("/banners/active")).andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(2)))
            .andExpect(jsonPath("$[0].uuid").value(expectedIds.get(0))).andExpect(jsonPath("$[1].uuid").value(expectedIds.get(1)));
    }

    @Test
    void otherBannerPathsAreNotAnonymous() throws Exception {
        mockMvc.perform(get("/banners")).andExpect(status().isForbidden());
    }

    @Test
    void adminAndSuperAdminCanPublishButOtherPrivilegesCannot() throws Exception {
        String request = publishRequest("<p>Authorized</p>", null);

        mockMvc.perform(
            post("/banners").header(GatewayUserResolver.HEADER_USER_ID, "admin-id")
                .header(GatewayUserResolver.HEADER_USER_PRIVILEGES, "ADMIN").contentType(MediaType.APPLICATION_JSON).content(request)
        ).andExpect(status().isCreated());
        mockMvc.perform(
            post("/banners").header(GatewayUserResolver.HEADER_USER_ID, "super-id")
                .header(GatewayUserResolver.HEADER_USER_PRIVILEGES, "SUPER_ADMIN").contentType(MediaType.APPLICATION_JSON).content(request)
        ).andExpect(status().isCreated());
        mockMvc.perform(
            post("/banners").header(GatewayUserResolver.HEADER_USER_ID, "researcher-id")
                .header(GatewayUserResolver.HEADER_USER_PRIVILEGES, "PIC_SURE_ANY_QUERY").contentType(MediaType.APPLICATION_JSON)
                .content(request)
        ).andExpect(status().isForbidden());
        mockMvc.perform(post("/banners").contentType(MediaType.APPLICATION_JSON).content(request)).andExpect(status().isForbidden());
    }

    @Test
    void publishRejectsHtmlAndTitleOverTheirServerLimits() throws Exception {
        mockMvc.perform(adminPost(publishRequest("x".repeat(5_001), null))).andExpect(status().isBadRequest());
        mockMvc.perform(adminPost(publishRequest("<p>Valid</p>", "x".repeat(121)))).andExpect(status().isBadRequest());

        org.assertj.core.api.Assertions.assertThat(repository.count()).isZero();
        verifyNoInteractions(loggingClient);
    }

    @Test
    void publishReturnsAuthoritativeRecordAndAssignsBottomPriority() throws Exception {
        repository.save(banner(8, BannerStatus.PUBLISHED, NOW.minusSeconds(60), null, "Active"));
        repository.save(banner(13, BannerStatus.PUBLISHED, NOW.plusSeconds(60), null, "Scheduled"));
        repository.save(banner(50, BannerStatus.PUBLISHED, NOW.minusSeconds(120), NOW, "Expired"));
        repository.save(banner(70, BannerStatus.DISABLED, NOW.minusSeconds(120), null, "Disabled"));
        String submittedHtml = "<p>Exact bytes:  two spaces</p>";
        UUID clientUuid = UUID.fromString("00000000-0000-0000-0000-000000000001");
        ObjectNode request = (ObjectNode) objectMapper.readTree(publishRequest(submittedHtml, " Notice "));
        request.put("uuid", clientUuid.toString()).put("status", "ARCHIVED").put("priority", 999).put("presentationHash", "client-hash")
            .put("startAt", NOW.plusSeconds(3_600).toString()).put("createdBy", "spoofed-actor");

        mockMvc.perform(adminPost(request.toString())).andExpect(status().isCreated())
            .andExpect(jsonPath("$.uuid").value(not(clientUuid.toString()))).andExpect(jsonPath("$.status").value("PUBLISHED"))
            .andExpect(jsonPath("$.htmlContent").value(submittedHtml)).andExpect(jsonPath("$.title").value("Notice"))
            .andExpect(jsonPath("$.startAt").value(NOW.toString())).andExpect(jsonPath("$.createdAt").value(NOW.toString()))
            .andExpect(jsonPath("$.updatedAt").value(NOW.toString())).andExpect(jsonPath("$.publishedAt").value(NOW.toString()))
            .andExpect(jsonPath("$.createdBy").value("admin-id")).andExpect(jsonPath("$.updatedBy").value("admin-id"))
            .andExpect(jsonPath("$.publishedBy").value("admin-id")).andExpect(jsonPath("$.priority").value(14))
            .andExpect(jsonPath("$.presentationHash").value(org.hamcrest.Matchers.matchesPattern("[0-9a-f]{64}")));
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder adminPost(String content) {
        return post("/banners").header(GatewayUserResolver.HEADER_USER_ID, "admin-id")
            .header(GatewayUserResolver.HEADER_USER_PRIVILEGES, "ADMIN").contentType(MediaType.APPLICATION_JSON).content(content);
    }

    private String publishRequest(String htmlContent, String title) throws Exception {
        return objectMapper.writeValueAsString(
            Map.of(
                "htmlContent", htmlContent, "title", title == null ? "" : title, "appearance", "PRIMARY", "icon", "INFORMATION",
                "dismissible", true, "audience", "EVERYONE", "placement", "SITE_TOP", "pageTargets", List.of(Map.of("kind", "ALL"))
            )
        );
    }

    private static BannerOccurrence banner(int priority, BannerStatus status, Instant startAt, Instant endAt, String title) {
        return new BannerOccurrence().setStatus(status).setHtmlContent("<p>" + title + " content</p>").setTitle(title)
            .setAppearance(BannerAppearance.PRIMARY).setIcon(BannerIcon.INFORMATION).setDismissible(true)
            .setAudience(BannerAudience.EVERYONE).setPlacement(BannerPlacement.SITE_TOP)
            .setPageTargets(JsonNodeFactory.instance.arrayNode().add(JsonNodeFactory.instance.objectNode().put("kind", "ALL")))
            .setStartAt(startAt).setEndAt(endAt).setPriority(priority).setPresentationHash("hash-" + title)
            .setCreatedAt(NOW.minusSeconds(120)).setCreatedBy("admin").setUpdatedAt(NOW.minusSeconds(60)).setUpdatedBy("admin")
            .setPublishedAt(NOW.minusSeconds(60)).setPublishedBy("admin");
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
