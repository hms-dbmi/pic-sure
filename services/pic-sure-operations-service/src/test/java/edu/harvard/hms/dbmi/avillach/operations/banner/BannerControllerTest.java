package edu.harvard.hms.dbmi.avillach.operations.banner;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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

    @Autowired
    private BannerPresentationHasher hasher;

    @Autowired
    private BannerVersionRepository versionRepository;

    @Autowired
    private BannerPriorityAllocatorRepository priorityAllocatorRepository;

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
        mockMvc.perform(post("/banners").contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isForbidden());
        mockMvc.perform(post("/banners/saved").contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isForbidden());
        mockMvc.perform(put("/banners/order").contentType(MediaType.APPLICATION_JSON).content("{\"bannerUuids\":[]}"))
            .andExpect(status().isForbidden());
        mockMvc.perform(put("/banners/{uuid}", UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isForbidden());
        mockMvc.perform(post("/banners/{uuid}/publish", UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isForbidden());
        mockMvc.perform(post("/banners/{uuid}/disable", UUID.randomUUID())).andExpect(status().isForbidden());
        mockMvc.perform(post("/banners/{uuid}/archive", UUID.randomUUID())).andExpect(status().isForbidden());
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
    void allMutationsRejectHtmlAndTitleOverTheirServerLimits() throws Exception {
        UUID savedUuid = repository.save(banner(null, BannerStatus.SAVED, null, null, "Saved validation target")).getUuid();
        List<org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder> oversizedHtml = List.of(
            adminPost(publishRequest("x".repeat(5_001), null)), adminPost("/banners/saved", publishRequest("x".repeat(5_001), null)),
            adminPut(savedUuid, publishRequest("x".repeat(5_001), null)),
            adminPost("/banners/{uuid}/publish", savedUuid, publishRequest("x".repeat(5_001), null))
        );
        List<org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder> oversizedTitle = List.of(
            adminPost(publishRequest("<p>Valid</p>", "x".repeat(121))),
            adminPost("/banners/saved", publishRequest("<p>Valid</p>", "x".repeat(121))),
            adminPut(savedUuid, publishRequest("<p>Valid</p>", "x".repeat(121))),
            adminPost("/banners/{uuid}/publish", savedUuid, publishRequest("<p>Valid</p>", "x".repeat(121)))
        );

        for (var request : oversizedHtml) {
            mockMvc.perform(request).andExpect(status().isBadRequest());
        }
        for (var request : oversizedTitle) {
            mockMvc.perform(request).andExpect(status().isBadRequest());
        }

        org.assertj.core.api.Assertions.assertThat(repository.count()).isOne();
        org.assertj.core.api.Assertions.assertThat(repository.findById(savedUuid).orElseThrow().getTitle())
            .isEqualTo("Saved validation target");
        verifyNoInteractions(loggingClient);
    }

    @Test
    void archivedOccurrencesHaveNoManagementRepresentation() {
        BannerOccurrence archived = banner(null, BannerStatus.ARCHIVED, null, null, "Archived");

        org.assertj.core.api.Assertions.assertThat(ManagementBannerDto.from(archived, NOW)).isEmpty();
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
            .put("createdBy", "spoofed-actor");

        mockMvc.perform(adminPost(request.toString())).andExpect(status().isCreated())
            .andExpect(jsonPath("$.uuid").value(not(clientUuid.toString()))).andExpect(jsonPath("$.status").value("PUBLISHED"))
            .andExpect(jsonPath("$.htmlContent").value(submittedHtml)).andExpect(jsonPath("$.title").value("Notice"))
            .andExpect(jsonPath("$.startAt").value(NOW.toString())).andExpect(jsonPath("$.createdAt").value(NOW.toString()))
            .andExpect(jsonPath("$.updatedAt").value(NOW.toString())).andExpect(jsonPath("$.publishedAt").value(NOW.toString()))
            .andExpect(jsonPath("$.createdBy").value("admin-id")).andExpect(jsonPath("$.updatedBy").value("admin-id"))
            .andExpect(jsonPath("$.publishedBy").value("admin-id")).andExpect(jsonPath("$.priority").value(14))
            .andExpect(jsonPath("$.presentationHash").value(org.hamcrest.Matchers.matchesPattern("[0-9a-f]{64}")));
    }

    @Test
    void reorderIgnoresPersistedDeparturesAppendsArrivalsAndCompactsTheCanonicalQueue() throws Exception {
        BannerOccurrence first = repository.save(banner(8, BannerStatus.PUBLISHED, NOW.minusSeconds(60), null, "First"));
        BannerOccurrence second = repository.save(banner(21, BannerStatus.PUBLISHED, NOW.plusSeconds(60), null, "Second"));
        BannerOccurrence arrival = repository.save(banner(34, BannerStatus.PUBLISHED, NOW.minusSeconds(60), null, "Arrival"));
        BannerOccurrence expired = repository.save(banner(13, BannerStatus.PUBLISHED, NOW.minusSeconds(120), NOW, "Expired"));
        BannerOccurrence disabled = repository.save(banner(55, BannerStatus.DISABLED, NOW.minusSeconds(120), null, "Disabled"));
        BannerOccurrence saved = repository.save(banner(null, BannerStatus.SAVED, null, null, "Saved"));

        mockMvc.perform(
            adminPut(
                "/banners/order",
                Map.of("bannerUuids", List.of(expired.getUuid(), second.getUuid(), disabled.getUuid(), saved.getUuid(), first.getUuid()))
            )
        ).andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(3)))
            .andExpect(jsonPath("$[0].uuid").value(second.getUuid().toString())).andExpect(jsonPath("$[0].priority").value(1))
            .andExpect(jsonPath("$[1].uuid").value(first.getUuid().toString())).andExpect(jsonPath("$[1].priority").value(2))
            .andExpect(jsonPath("$[2].uuid").value(arrival.getUuid().toString())).andExpect(jsonPath("$[2].priority").value(3));

        mockMvc.perform(get("/banners/active")).andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(2)))
            .andExpect(jsonPath("$[0].uuid").value(first.getUuid().toString())).andExpect(jsonPath("$[0].priority").value(2))
            .andExpect(jsonPath("$[1].uuid").value(arrival.getUuid().toString())).andExpect(jsonPath("$[1].priority").value(3));
    }

    @Test
    void emptyStaleReorderAppendsEveryCurrentMemberInPersistedOrder() throws Exception {
        BannerOccurrence first = repository.save(banner(8, BannerStatus.PUBLISHED, NOW.minusSeconds(60), null, "First"));
        BannerOccurrence second = repository.save(banner(21, BannerStatus.PUBLISHED, NOW.plusSeconds(60), null, "Second"));

        mockMvc.perform(adminPut("/banners/order", Map.of("bannerUuids", List.of()))).andExpect(status().isOk())
            .andExpect(jsonPath("$[0].uuid").value(first.getUuid().toString())).andExpect(jsonPath("$[0].priority").value(1))
            .andExpect(jsonPath("$[1].uuid").value(second.getUuid().toString())).andExpect(jsonPath("$[1].priority").value(2));
    }

    @Test
    void reorderRejectsDuplicateAndUnknownMembersWithoutChangingPriorities() throws Exception {
        BannerOccurrence first = repository.save(banner(8, BannerStatus.PUBLISHED, NOW.minusSeconds(60), null, "First"));
        BannerOccurrence second = repository.save(banner(21, BannerStatus.PUBLISHED, NOW.plusSeconds(60), null, "Second"));

        List<List<UUID>> invalidOrders = List.of(
            List.of(first.getUuid(), first.getUuid()), List.of(first.getUuid(), UUID.randomUUID())
        );
        for (List<UUID> invalidOrder : invalidOrders) {
            mockMvc.perform(adminPut("/banners/order", Map.of("bannerUuids", invalidOrder))).andExpect(status().isBadRequest());
            org.assertj.core.api.Assertions.assertThat(repository.findById(first.getUuid()).orElseThrow().getPriority()).isEqualTo(8);
            org.assertj.core.api.Assertions.assertThat(repository.findById(second.getUuid()).orElseThrow().getPriority()).isEqualTo(21);
        }
    }

    @Test
    void publishAcceptsAnExplicitUtcWindowAndReturnsScheduled() throws Exception {
        ObjectNode request = (ObjectNode) objectMapper.readTree(publishRequest("<p>Scheduled</p>", "Scheduled"));
        request.put("startAt", NOW.plusSeconds(60).toString()).put("endAt", NOW.plusSeconds(120).toString());

        mockMvc.perform(adminPost(request.toString())).andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("PUBLISHED"))
            .andExpect(jsonPath("$.lifecycle").value("SCHEDULED")).andExpect(jsonPath("$.startAt").value(NOW.plusSeconds(60).toString()))
            .andExpect(jsonPath("$.endAt").value(NOW.plusSeconds(120).toString()));
        mockMvc.perform(get("/banners/active")).andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    void savesUpdatesAndPublishesADraftAsTheSameOccurrence() throws Exception {
        String savedJson = mockMvc.perform(adminPost("/banners/saved", publishRequest("<p>Draft copy</p>", " Draft ")))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("SAVED")).andExpect(jsonPath("$.title").value("Draft"))
            .andExpect(jsonPath("$.startAt").doesNotExist()).andExpect(jsonPath("$.priority").doesNotExist())
            .andExpect(jsonPath("$.publishedAt").doesNotExist()).andReturn().getResponse().getContentAsString();
        UUID uuid = UUID.fromString(objectMapper.readTree(savedJson).get("uuid").asText());

        mockMvc.perform(get("/banners/active")).andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(0)));

        mockMvc
            .perform(
                put("/banners/{uuid}", uuid).header(GatewayUserResolver.HEADER_USER_ID, "super-id")
                    .header(GatewayUserResolver.HEADER_USER_PRIVILEGES, "SUPER_ADMIN").contentType(MediaType.APPLICATION_JSON)
                    .content(publishRequest("<p>Updated draft copy</p>", "Updated draft"))
            ).andExpect(status().isOk()).andExpect(jsonPath("$.uuid").value(uuid.toString())).andExpect(jsonPath("$.status").value("SAVED"))
            .andExpect(jsonPath("$.htmlContent").value("<p>Updated draft copy</p>")).andExpect(jsonPath("$.updatedBy").value("super-id"));
        org.assertj.core.api.Assertions.assertThat(versionRepository.findAll()).isEmpty();

        mockMvc
            .perform(
                post("/banners/{uuid}/publish", uuid).header(GatewayUserResolver.HEADER_USER_ID, "admin-id")
                    .header(GatewayUserResolver.HEADER_USER_PRIVILEGES, "ADMIN").contentType(MediaType.APPLICATION_JSON)
                    .content(publishRequest("<p>Published draft copy</p>", "Published draft"))
            ).andExpect(status().isOk()).andExpect(jsonPath("$.uuid").value(uuid.toString()))
            .andExpect(jsonPath("$.status").value("PUBLISHED")).andExpect(jsonPath("$.startAt").value(NOW.toString()))
            .andExpect(jsonPath("$.htmlContent").value("<p>Published draft copy</p>"))
            .andExpect(jsonPath("$.publishedBy").value("admin-id"));

        BannerOccurrence promoted = repository.findById(uuid).orElseThrow();
        org.assertj.core.api.Assertions.assertThat(promoted.getPresentationHash()).isEqualTo(hasher.hash(promoted));
        org.assertj.core.api.Assertions.assertThat(versionRepository.findAll()).singleElement()
            .satisfies(version -> org.assertj.core.api.Assertions.assertThat(version.getVersionNumber()).isEqualTo(1));

        mockMvc.perform(get("/banners/active")).andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(1)))
            .andExpect(jsonPath("$[0].uuid").value(uuid.toString()))
            .andExpect(jsonPath("$[0].htmlContent").value("<p>Published draft copy</p>"));
    }

    @Test
    void managementListExcludesArchivedAndReturnsDerivedLifecycle() throws Exception {
        BannerOccurrence active =
            banner(1, BannerStatus.PUBLISHED, NOW.minusSeconds(60), null, "Active").setCreatedAt(NOW.minusSeconds(30));
        BannerOccurrence scheduled =
            banner(2, BannerStatus.PUBLISHED, NOW.plusSeconds(60), null, "Scheduled").setCreatedAt(NOW.minusSeconds(300));
        repository.save(active);
        repository.save(scheduled);
        repository.save(banner(3, BannerStatus.PUBLISHED, NOW.minusSeconds(120), NOW, "Expired"));
        repository.save(banner(null, BannerStatus.SAVED, null, null, "Saved"));
        repository.save(banner(null, BannerStatus.DISABLED, NOW.minusSeconds(120), null, "Disabled"));
        repository.save(banner(null, BannerStatus.ARCHIVED, NOW.minusSeconds(120), null, "Archived"));

        mockMvc
            .perform(
                get("/banners").header(GatewayUserResolver.HEADER_USER_ID, "admin-id")
                    .header(GatewayUserResolver.HEADER_USER_PRIVILEGES, "ADMIN")
            ).andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(5))).andExpect(jsonPath("$[0].title").value("Active"))
            .andExpect(jsonPath("$[1].title").value("Scheduled")).andExpect(jsonPath("$[?(@.title == 'Active')].lifecycle").value("ACTIVE"))
            .andExpect(jsonPath("$[?(@.title == 'Scheduled')].lifecycle").value("SCHEDULED"))
            .andExpect(jsonPath("$[?(@.title == 'Expired')].lifecycle").value("EXPIRED"))
            .andExpect(jsonPath("$[?(@.title == 'Saved')].lifecycle").value("SAVED"))
            .andExpect(jsonPath("$[?(@.title == 'Disabled')].lifecycle").value("DISABLED"))
            .andExpect(jsonPath("$[?(@.title == 'Archived')]").isEmpty());
    }

    @Test
    void updateRouteDispatchesPublishedChangesAndRejectsOtherNonDraftStates() throws Exception {
        BannerOccurrence published = repository.save(banner(1, BannerStatus.PUBLISHED, NOW.minusSeconds(60), null, "Published"));
        BannerOccurrence disabled = repository.save(banner(2, BannerStatus.DISABLED, NOW.minusSeconds(60), null, "Disabled"));
        BannerOccurrence archived = repository.save(banner(2, BannerStatus.ARCHIVED, NOW.minusSeconds(60), null, "Archived"));

        mockMvc.perform(adminPut(published.getUuid(), publishRequest("<p>Changed published</p>", null))).andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("PUBLISHED")).andExpect(jsonPath("$.htmlContent").value("<p>Changed published</p>"));
        mockMvc.perform(adminPut(disabled.getUuid(), publishRequest("<p>Changed disabled</p>", null))).andExpect(status().isConflict());
        mockMvc.perform(adminPut(archived.getUuid(), publishRequest("<p>Changed archived</p>", null))).andExpect(status().isConflict());
        mockMvc.perform(adminPut(UUID.randomUUID(), publishRequest("<p>Missing</p>", null))).andExpect(status().isNotFound());

        mockMvc.perform(
            post("/banners/{uuid}/publish", published.getUuid()).header(GatewayUserResolver.HEADER_USER_ID, "admin-id")
                .header(GatewayUserResolver.HEADER_USER_PRIVILEGES, "ADMIN").contentType(MediaType.APPLICATION_JSON)
                .content(publishRequest("<p>Changed</p>", null))
        ).andExpect(status().isConflict());
        mockMvc.perform(
            post("/banners/{uuid}/publish", UUID.randomUUID()).header(GatewayUserResolver.HEADER_USER_ID, "admin-id")
                .header(GatewayUserResolver.HEADER_USER_PRIVILEGES, "ADMIN").contentType(MediaType.APPLICATION_JSON)
                .content(publishRequest("<p>Changed</p>", null))
        ).andExpect(status().isNotFound());
        mockMvc.perform(
            post("/banners/{uuid}/publish", archived.getUuid()).header(GatewayUserResolver.HEADER_USER_ID, "admin-id")
                .header(GatewayUserResolver.HEADER_USER_PRIVILEGES, "ADMIN").contentType(MediaType.APPLICATION_JSON)
                .content(publishRequest("<p>Changed</p>", null))
        ).andExpect(status().isConflict());
    }

    @Test
    void publishedEditReturnsTheAuthoritativeUpdateAndChangesThePublicFeed() throws Exception {
        String create = publishRequest("<p>Original bytes</p>", "Original");
        String response = mockMvc.perform(adminPost(create)).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        UUID uuid = UUID.fromString(objectMapper.readTree(response).get("uuid").asText());
        ObjectNode update = (ObjectNode) objectMapper.readTree(publishRequest("<p>Corrected bytes</p>", "Corrected"));
        update.put("appearance", "ERROR").put("icon", "ERROR").put("dismissible", false);

        mockMvc
            .perform(
                put("/banners/{uuid}", uuid).header(GatewayUserResolver.HEADER_USER_ID, "admin-id")
                    .header(GatewayUserResolver.HEADER_USER_PRIVILEGES, "ADMIN").contentType(MediaType.APPLICATION_JSON)
                    .content(update.toString())
            ).andExpect(status().isOk()).andExpect(jsonPath("$.uuid").value(uuid.toString()))
            .andExpect(jsonPath("$.htmlContent").value("<p>Corrected bytes</p>")).andExpect(jsonPath("$.title").value("Corrected"))
            .andExpect(jsonPath("$.appearance").value("ERROR")).andExpect(jsonPath("$.icon").value("ERROR"))
            .andExpect(jsonPath("$.dismissible").value(false));

        mockMvc.perform(get("/banners/active")).andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(1)))
            .andExpect(jsonPath("$[0].uuid").value(uuid.toString())).andExpect(jsonPath("$[0].htmlContent").value("<p>Corrected bytes</p>"))
            .andExpect(jsonPath("$[0].title").value("Corrected")).andExpect(jsonPath("$[0].appearance").value("ERROR"));
    }

    @Test
    void nonAdminsAndAnonymousUsersCannotEditPublishedBanners() throws Exception {
        String response = mockMvc.perform(adminPost(publishRequest("<p>Original</p>", "Original"))).andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        UUID uuid = UUID.fromString(objectMapper.readTree(response).get("uuid").asText());
        String update = publishRequest("<p>Unauthorized change</p>", "Unauthorized");

        mockMvc.perform(
            put("/banners/{uuid}", uuid).header(GatewayUserResolver.HEADER_USER_ID, "researcher-id")
                .header(GatewayUserResolver.HEADER_USER_PRIVILEGES, "PIC_SURE_ANY_QUERY").contentType(MediaType.APPLICATION_JSON)
                .content(update)
        ).andExpect(status().isForbidden());
        mockMvc.perform(put("/banners/{uuid}", uuid).contentType(MediaType.APPLICATION_JSON).content(update))
            .andExpect(status().isForbidden());

        mockMvc.perform(get("/banners/active")).andExpect(status().isOk()).andExpect(jsonPath("$[0].htmlContent").value("<p>Original</p>"));
    }

    @Test
    void disableRemovesAnActiveOccurrenceFromTheFeedAndRecordsProvenance() throws Exception {
        String response = mockMvc.perform(adminPost(publishRequest("<p>Live notice</p>", "Live"))).andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        UUID uuid = UUID.fromString(objectMapper.readTree(response).get("uuid").asText());
        int priority = repository.findById(uuid).orElseThrow().getPriority();
        int nextPriority = priorityAllocatorRepository.findById(BannerPriorityAllocator.SINGLETON_ID).orElseThrow().getNextPriority();
        mockMvc.perform(get("/banners/active")).andExpect(jsonPath("$", hasSize(1)));

        mockMvc
            .perform(
                post("/banners/{uuid}/disable", uuid).header(GatewayUserResolver.HEADER_USER_ID, "super-id")
                    .header(GatewayUserResolver.HEADER_USER_PRIVILEGES, "SUPER_ADMIN")
            ).andExpect(status().isOk()).andExpect(jsonPath("$.uuid").value(uuid.toString()))
            .andExpect(jsonPath("$.status").value("DISABLED")).andExpect(jsonPath("$.lifecycle").value("DISABLED"))
            .andExpect(jsonPath("$.disabledAt").value(NOW.toString())).andExpect(jsonPath("$.disabledBy").value("super-id"))
            .andExpect(jsonPath("$.htmlContent").value("<p>Live notice</p>")).andExpect(jsonPath("$.startAt").value(NOW.toString()))
            .andExpect(jsonPath("$.publishedBy").value("admin-id"));

        mockMvc.perform(get("/banners/active")).andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(0)));
        BannerOccurrence disabled = repository.findById(uuid).orElseThrow();
        org.assertj.core.api.Assertions.assertThat(disabled.getHtmlContent()).isEqualTo("<p>Live notice</p>");
        org.assertj.core.api.Assertions.assertThat(disabled.getPriority()).isEqualTo(priority);
        org.assertj.core.api.Assertions
            .assertThat(priorityAllocatorRepository.findById(BannerPriorityAllocator.SINGLETON_ID).orElseThrow().getNextPriority())
            .isEqualTo(nextPriority);
        org.assertj.core.api.Assertions.assertThat(disabled.getPresentationHash()).isEqualTo(hasher.hash(disabled));
        org.assertj.core.api.Assertions.assertThat(disabled.getPublishedAt()).isEqualTo(NOW);
        org.assertj.core.api.Assertions.assertThat(versionRepository.findAll()).singleElement().satisfies(version -> {
            org.assertj.core.api.Assertions.assertThat(version.getVersionNumber()).isOne();
            org.assertj.core.api.Assertions.assertThat(version.getHtmlContent()).isEqualTo("<p>Live notice</p>");
        });
    }

    @Test
    void disableMovesAScheduledOccurrenceOutOfTheOrderableQueue() throws Exception {
        ObjectNode request = (ObjectNode) objectMapper.readTree(publishRequest("<p>Upcoming</p>", "Upcoming"));
        request.put("startAt", NOW.plusSeconds(3_600).toString());
        String response =
            mockMvc.perform(adminPost(request.toString())).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        UUID uuid = UUID.fromString(objectMapper.readTree(response).get("uuid").asText());

        mockMvc.perform(adminDisable(uuid)).andExpect(status().isOk()).andExpect(jsonPath("$.lifecycle").value("DISABLED"))
            .andExpect(jsonPath("$.startAt").value(NOW.plusSeconds(3_600).toString()));

        mockMvc.perform(
            get("/banners").header(GatewayUserResolver.HEADER_USER_ID, "admin-id")
                .header(GatewayUserResolver.HEADER_USER_PRIVILEGES, "ADMIN")
        ).andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(1))).andExpect(jsonPath("$[0].lifecycle").value("DISABLED"));
    }

    @Test
    void disableRejectsSavedExpiredAlreadyDisabledAndArchivedOccurrencesWithoutChangingState() throws Exception {
        BannerOccurrence saved = repository.save(banner(null, BannerStatus.SAVED, null, null, "Saved"));
        BannerOccurrence expired = repository.save(banner(1, BannerStatus.PUBLISHED, NOW.minusSeconds(120), NOW, "Expired"));
        BannerOccurrence alreadyDisabled = repository.save(banner(2, BannerStatus.DISABLED, NOW.minusSeconds(120), null, "Disabled"));
        BannerOccurrence archived = repository.save(banner(3, BannerStatus.ARCHIVED, NOW.minusSeconds(120), null, "Archived"));

        for (BannerOccurrence rejected : List.of(saved, expired, alreadyDisabled, archived)) {
            mockMvc.perform(adminDisable(rejected.getUuid())).andExpect(status().isConflict());
        }
        mockMvc.perform(adminDisable(UUID.randomUUID())).andExpect(status().isNotFound());

        org.assertj.core.api.Assertions.assertThat(repository.findById(saved.getUuid()).orElseThrow().getStatus())
            .isEqualTo(BannerStatus.SAVED);
        org.assertj.core.api.Assertions.assertThat(repository.findById(expired.getUuid()).orElseThrow().getStatus())
            .isEqualTo(BannerStatus.PUBLISHED);
        org.assertj.core.api.Assertions.assertThat(repository.findById(archived.getUuid()).orElseThrow().getStatus())
            .isEqualTo(BannerStatus.ARCHIVED);
        org.assertj.core.api.Assertions.assertThat(repository.findById(alreadyDisabled.getUuid()).orElseThrow().getDisabledAt()).isNull();
        verifyNoInteractions(loggingClient);
    }

    @Test
    void nonAdminsAndAnonymousUsersCannotDisableBanners() throws Exception {
        String response = mockMvc.perform(adminPost(publishRequest("<p>Original</p>", "Original"))).andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        UUID uuid = UUID.fromString(objectMapper.readTree(response).get("uuid").asText());

        mockMvc.perform(
            post("/banners/{uuid}/disable", uuid).header(GatewayUserResolver.HEADER_USER_ID, "researcher-id")
                .header(GatewayUserResolver.HEADER_USER_PRIVILEGES, "PIC_SURE_ANY_QUERY")
        ).andExpect(status().isForbidden());
        mockMvc.perform(post("/banners/{uuid}/disable", uuid)).andExpect(status().isForbidden());

        mockMvc.perform(get("/banners/active")).andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(1)));
    }

    @Test
    void archiveReturnsASmallAuthoritativeResultAndDropsTheOccurrenceFromNormalApis() throws Exception {
        UUID disabled = repository.save(banner(4, BannerStatus.DISABLED, NOW.minusSeconds(120), null, "Disabled")).getUuid();
        repository.save(banner(5, BannerStatus.PUBLISHED, NOW.minusSeconds(120), null, "Active"));

        mockMvc
            .perform(
                post("/banners/{uuid}/archive", disabled).header(GatewayUserResolver.HEADER_USER_ID, "super-id")
                    .header(GatewayUserResolver.HEADER_USER_PRIVILEGES, "SUPER_ADMIN")
            ).andExpect(status().isOk()).andExpect(jsonPath("$.uuid").value(disabled.toString()))
            .andExpect(jsonPath("$.status").value("ARCHIVED")).andExpect(jsonPath("$.archivedAt").value(NOW.toString()))
            .andExpect(jsonPath("$.archivedBy").value("super-id")).andExpect(jsonPath("$.lifecycle").doesNotExist())
            .andExpect(jsonPath("$.htmlContent").doesNotExist()).andExpect(jsonPath("$.title").doesNotExist())
            .andExpect(jsonPath("$.pageTargets").doesNotExist()).andExpect(jsonPath("$.presentationHash").doesNotExist());

        mockMvc.perform(
            get("/banners").header(GatewayUserResolver.HEADER_USER_ID, "admin-id")
                .header(GatewayUserResolver.HEADER_USER_PRIVILEGES, "ADMIN")
        ).andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(1))).andExpect(jsonPath("$[0].title").value("Active"));
        mockMvc.perform(get("/banners/active")).andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(1)))
            .andExpect(jsonPath("$[0].title").value("Active"));
    }

    @Test
    void archiveRejectsDisplayableAlreadyArchivedAndUnknownOccurrencesOverHttp() throws Exception {
        BannerOccurrence active = repository.save(banner(1, BannerStatus.PUBLISHED, NOW.minusSeconds(120), null, "Active"));
        BannerOccurrence scheduled = repository.save(banner(2, BannerStatus.PUBLISHED, NOW.plusSeconds(120), null, "Scheduled"));
        BannerOccurrence archived = repository.save(banner(3, BannerStatus.ARCHIVED, NOW.minusSeconds(120), null, "Archived"));

        for (BannerOccurrence rejected : List.of(active, scheduled, archived)) {
            mockMvc.perform(adminArchive(rejected.getUuid())).andExpect(status().isConflict());
        }
        mockMvc.perform(adminArchive(UUID.randomUUID())).andExpect(status().isNotFound());

        org.assertj.core.api.Assertions.assertThat(repository.findById(active.getUuid()).orElseThrow().getStatus())
            .isEqualTo(BannerStatus.PUBLISHED);
        org.assertj.core.api.Assertions.assertThat(repository.findById(scheduled.getUuid()).orElseThrow().getStatus())
            .isEqualTo(BannerStatus.PUBLISHED);
        org.assertj.core.api.Assertions.assertThat(repository.findById(archived.getUuid()).orElseThrow().getArchivedAt()).isNull();
        verifyNoInteractions(loggingClient);
    }

    @Test
    void adminAndSuperAdminCanArchiveButOtherPrivilegesCannot() throws Exception {
        UUID first = repository.save(banner(null, BannerStatus.SAVED, null, null, "First draft")).getUuid();
        UUID second = repository.save(banner(null, BannerStatus.SAVED, null, null, "Second draft")).getUuid();
        UUID guarded = repository.save(banner(null, BannerStatus.SAVED, null, null, "Guarded draft")).getUuid();

        mockMvc.perform(adminArchive(first)).andExpect(status().isOk());
        mockMvc.perform(
            post("/banners/{uuid}/archive", second).header(GatewayUserResolver.HEADER_USER_ID, "super-id")
                .header(GatewayUserResolver.HEADER_USER_PRIVILEGES, "SUPER_ADMIN")
        ).andExpect(status().isOk());
        mockMvc.perform(
            post("/banners/{uuid}/archive", guarded).header(GatewayUserResolver.HEADER_USER_ID, "researcher-id")
                .header(GatewayUserResolver.HEADER_USER_PRIVILEGES, "PIC_SURE_ANY_QUERY")
        ).andExpect(status().isForbidden());
        mockMvc.perform(post("/banners/{uuid}/archive", guarded)).andExpect(status().isForbidden());

        org.assertj.core.api.Assertions.assertThat(repository.findById(guarded).orElseThrow().getStatus()).isEqualTo(BannerStatus.SAVED);
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder adminArchive(UUID uuid) {
        return post("/banners/{uuid}/archive", uuid).header(GatewayUserResolver.HEADER_USER_ID, "admin-id")
            .header(GatewayUserResolver.HEADER_USER_PRIVILEGES, "ADMIN");
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder adminDisable(UUID uuid) {
        return post("/banners/{uuid}/disable", uuid).header(GatewayUserResolver.HEADER_USER_ID, "admin-id")
            .header(GatewayUserResolver.HEADER_USER_PRIVILEGES, "ADMIN");
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder adminPost(String content) {
        return adminPost("/banners", content);
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder adminPost(String path, String content) {
        return post(path).header(GatewayUserResolver.HEADER_USER_ID, "admin-id").header(GatewayUserResolver.HEADER_USER_PRIVILEGES, "ADMIN")
            .contentType(MediaType.APPLICATION_JSON).content(content);
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder adminPost(String path, UUID uuid, String content) {
        return post(path, uuid).header(GatewayUserResolver.HEADER_USER_ID, "admin-id")
            .header(GatewayUserResolver.HEADER_USER_PRIVILEGES, "ADMIN").contentType(MediaType.APPLICATION_JSON).content(content);
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder adminPut(UUID uuid, String content) {
        return put("/banners/{uuid}", uuid).header(GatewayUserResolver.HEADER_USER_ID, "admin-id")
            .header(GatewayUserResolver.HEADER_USER_PRIVILEGES, "ADMIN").contentType(MediaType.APPLICATION_JSON).content(content);
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder adminPut(String path, Object content)
        throws Exception {
        return put(path).header(GatewayUserResolver.HEADER_USER_ID, "admin-id").header(GatewayUserResolver.HEADER_USER_PRIVILEGES, "ADMIN")
            .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(content));
    }

    private String publishRequest(String htmlContent, String title) throws Exception {
        return objectMapper.writeValueAsString(
            Map.of(
                "htmlContent", htmlContent, "title", title == null ? "" : title, "appearance", "PRIMARY", "icon", "INFORMATION",
                "dismissible", true, "audience", "EVERYONE", "placement", "SITE_TOP", "pageTargets", List.of(Map.of("kind", "ALL"))
            )
        );
    }

    private static BannerOccurrence banner(Integer priority, BannerStatus status, Instant startAt, Instant endAt, String title) {
        return new BannerOccurrence().setStatus(status).setHtmlContent("<p>" + title + " content</p>").setTitle(title)
            .setAppearance(BannerAppearance.PRIMARY).setIcon(BannerIcon.INFORMATION).setDismissible(true)
            .setAudience(BannerAudience.EVERYONE).setPlacement(BannerPlacement.SITE_TOP).setPageTargets(List.of(BannerPageTarget.all()))
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
