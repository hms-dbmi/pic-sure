package edu.harvard.hms.dbmi.avillach.operations.query;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;


/**
 * Full-context MockMvc test exercising the real {@code InternalTokenFilter} together with {@link InternalSiteController}, same posture as
 * {@code InternalQueryControllerTest}: no mocked security layer.
 *
 * <p>FIXED CONTRACT: the hpds-query-service's {@code OperationsClient.findSitesByDomain(domain)} calls {@code GET
 * /internal/sites/by-domain/{domain}} expecting a JSON {@code List<String>} of the matching {@code Site}'s {@code code} -- 0 or 1 elements,
 * since {@code domain} is unique.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class InternalSiteControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private SiteRepository siteRepo;

    @Value("${picsure.operations.internal-token}")
    private String validToken;

    @Test
    void knownDomainReturnsSingletonListOfCode() throws Exception {
        Site site = new Site();
        site.setCode("BCH");
        site.setName("Boston Children's");
        site.setDomain("childrens.harvard.edu");
        siteRepo.save(site);

        mockMvc.perform(get("/internal/sites/by-domain/{domain}", "childrens.harvard.edu").header(InternalTokenFilter.HEADER, validToken))
            .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0]").value("BCH"));
    }

    @Test
    void unknownDomainReturnsEmptyList() throws Exception {
        mockMvc.perform(get("/internal/sites/by-domain/{domain}", "nowhere.example.org").header(InternalTokenFilter.HEADER, validToken))
            .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void missingTokenIsForbidden() throws Exception {
        mockMvc.perform(get("/internal/sites/by-domain/{domain}", "childrens.harvard.edu")).andExpect(status().isForbidden());
    }

    @Test
    void wrongTokenIsForbidden() throws Exception {
        mockMvc
            .perform(get("/internal/sites/by-domain/{domain}", "childrens.harvard.edu").header(InternalTokenFilter.HEADER, "wrong-token"))
            .andExpect(status().isForbidden());
    }
}
