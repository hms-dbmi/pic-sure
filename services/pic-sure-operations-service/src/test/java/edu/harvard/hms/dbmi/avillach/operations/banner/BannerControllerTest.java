package edu.harvard.hms.dbmi.avillach.operations.banner;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class BannerControllerTest {

    private static final Instant NOW = Instant.parse("2026-08-27T12:00:00Z");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private BannerRepository repository;

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
    void otherBannerPathsAreNotAnonymous() throws Exception {
        mockMvc.perform(get("/banners")).andExpect(status().isForbidden());
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
        Clock fixedClock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }
    }
}
