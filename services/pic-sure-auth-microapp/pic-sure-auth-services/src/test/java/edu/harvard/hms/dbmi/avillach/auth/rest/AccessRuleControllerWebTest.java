package edu.harvard.hms.dbmi.avillach.auth.rest;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import edu.harvard.hms.dbmi.avillach.auth.entity.AccessRule;
import edu.harvard.hms.dbmi.avillach.auth.utils.AuthNaming;

/**
 * Sends requests for {@code AccessRuleController} through the servlet context path, the security filter chain, method security and request
 * mapping. The full application context runs on the same in-memory H2 settings as {@code HandlerAuthorizationTest}, so the two share one
 * cached context.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.MOCK,
    properties = {"spring.datasource.url=jdbc:h2:mem:psama-openapi;MODE=MySQL;DB_CLOSE_DELAY=-1;NON_KEYWORDS=USER,VALUE,KEY",
        "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa", "spring.datasource.password=",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect", "spring.jpa.hibernate.ddl-auto=create-drop",
        "APPLICATION_CLIENT_SECRET=openapi-test-placeholder-secret", "management.endpoints.web.exposure.include=none"}
)
@AutoConfigureMockMvc
class AccessRuleControllerWebTest {

    private static final String CONTEXT_PATH = "/auth";

    @Autowired
    private MockMvc mockMvc;

    /**
     * A GET carries no body, so a client sends no {@code Content-Type}. The rule types endpoint must still reach its handler and answer
     * with the type map for a caller holding {@code SUPER_ADMIN}.
     */
    @Test
    void allTypesAnswersAGetWithoutAContentType() throws Exception {
        mockMvc.perform(
            get(CONTEXT_PATH + "/accessRule/allTypes").contextPath(CONTEXT_PATH)
                .with(user("rule-type-reader").authorities(() -> AuthNaming.AuthRoleNaming.SUPER_ADMIN))
        ).andExpect(status().isOk()).andExpect(jsonPath("$.ALL_EQUALS").value(AccessRule.TypeNaming.ALL_EQUALS));
    }
}
