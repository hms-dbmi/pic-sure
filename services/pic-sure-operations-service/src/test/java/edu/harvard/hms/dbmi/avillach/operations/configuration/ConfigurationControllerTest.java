package edu.harvard.hms.dbmi.avillach.operations.configuration;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import edu.harvard.hms.dbmi.avillach.commons.identity.GatewayUserResolver;

/**
 * Full-context MockMvc test: exercises the real {@link edu.harvard.hms.dbmi.avillach.operations.config.WebSecurityConfig} filter chain (not
 * a mocked security layer), so SUPER_ADMIN enforcement is proven end-to-end. Requests simulate the gateway by setting the {@code X-User-*}
 * headers {@link GatewayUserResolver} reads -- there is no login/session in this service.
 *
 * <p>{@code @Transactional} wraps each test method (and the in-thread MockMvc dispatch) in a transaction rolled back afterward, so
 * configuration rows created by one test don't leak into the next.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ConfigurationControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ConfigurationRepository repo;

    @Test
    void publicListWorksUnauthenticated() throws Exception {
        repo.save(new Configuration().setName("feature-x").setKind("ui"));

        mockMvc.perform(get("/configuration")).andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(1)))
            .andExpect(jsonPath("$[0].name").value("feature-x"));
    }

    @Test
    void publicListFiltersByKind() throws Exception {
        repo.save(new Configuration().setName("feature-x").setKind("ui"));
        repo.save(new Configuration().setName("feature-y").setKind("backend"));

        mockMvc.perform(get("/configuration").param("kind", "ui")).andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(1)))
            .andExpect(jsonPath("$[0].kind").value("ui"));
    }

    @Test
    void publicGetByUuidWorksUnauthenticated() throws Exception {
        Configuration saved = repo.save(new Configuration().setName("feature-x").setKind("ui"));

        mockMvc.perform(get("/configuration/{id}", saved.getUuid())).andExpect(status().isOk())
            .andExpect(jsonPath("$.name").value("feature-x"));
    }

    @Test
    void publicGetByNameWorksUnauthenticated() throws Exception {
        repo.save(new Configuration().setName("feature-x").setKind("ui"));

        mockMvc.perform(get("/configuration/{id}", "feature-x")).andExpect(status().isOk()).andExpect(jsonPath("$.kind").value("ui"));
    }

    @Test
    void publicGetUnknownIdentifierReturns404() throws Exception {
        mockMvc.perform(get("/configuration/{id}", "does-not-exist")).andExpect(status().isNotFound())
            .andExpect(jsonPath("$.errorType").value("not_found"));
    }

    @Test
    void adminCreateWithoutSuperAdminIsForbidden() throws Exception {
        mockMvc.perform(
            post("/configuration/admin").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"A\",\"kind\":\"ui\",\"value\":\"true\"}")
        ).andExpect(status().isForbidden());
    }

    @Test
    void adminCreateAsPlainAuthenticatedUserIsForbidden() throws Exception {
        mockMvc.perform(
            post("/configuration/admin").header(GatewayUserResolver.HEADER_USER_ID, "auth0|abc")
                .header(GatewayUserResolver.HEADER_USER_PRIVILEGES, "USER").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"A\",\"kind\":\"ui\",\"value\":\"true\"}")
        ).andExpect(status().isForbidden());
    }

    @Test
    void adminCreateAsSuperAdminSucceedsWith200AndBody() throws Exception {
        mockMvc.perform(
            post("/configuration/admin").header(GatewayUserResolver.HEADER_USER_ID, "auth0|abc")
                .header(GatewayUserResolver.HEADER_USER_PRIVILEGES, "SUPER_ADMIN").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"A\",\"kind\":\"ui\",\"value\":\"true\",\"description\":\"desc\"}")
        ).andExpect(status().isOk()).andExpect(jsonPath("$.name").value("A")).andExpect(jsonPath("$.uuid").exists());
    }

    @Test
    void adminCreateMissingRequiredFieldReturns400() throws Exception {
        mockMvc.perform(
            post("/configuration/admin").header(GatewayUserResolver.HEADER_USER_ID, "auth0|abc")
                .header(GatewayUserResolver.HEADER_USER_PRIVILEGES, "SUPER_ADMIN").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"A\",\"kind\":\"ui\"}")
        ).andExpect(status().isBadRequest());
    }

    @Test
    void adminCreateDuplicateNameKindReturns409() throws Exception {
        repo.save(new Configuration().setName("A").setKind("ui").setValue("true"));

        mockMvc.perform(
            post("/configuration/admin").header(GatewayUserResolver.HEADER_USER_ID, "auth0|abc")
                .header(GatewayUserResolver.HEADER_USER_PRIVILEGES, "SUPER_ADMIN").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"A\",\"kind\":\"ui\",\"value\":\"false\"}")
        ).andExpect(status().isConflict()).andExpect(jsonPath("$.errorType").value("conflict"));
    }

    @Test
    void adminUpdateWithoutSuperAdminIsForbidden() throws Exception {
        Configuration saved = repo.save(new Configuration().setName("A").setKind("ui").setValue("true"));

        mockMvc.perform(
            patch("/configuration/admin/{id}", saved.getUuid()).contentType(MediaType.APPLICATION_JSON).content("{\"value\":\"false\"}")
        ).andExpect(status().isForbidden());
    }

    @Test
    void adminUpdateAsSuperAdminAppliesPartialPatch() throws Exception {
        Configuration saved = repo.save(new Configuration().setName("A").setKind("ui").setValue("true").setDescription("old"));

        mockMvc.perform(
            patch("/configuration/admin/{id}", saved.getUuid()).header(GatewayUserResolver.HEADER_USER_ID, "auth0|abc")
                .header(GatewayUserResolver.HEADER_USER_PRIVILEGES, "SUPER_ADMIN").contentType(MediaType.APPLICATION_JSON)
                .content("{\"value\":\"false\"}")
        ).andExpect(status().isOk()).andExpect(jsonPath("$.value").value("false")).andExpect(jsonPath("$.description").value("old"));
    }

    @Test
    void adminUpdateWithMismatchedBodyUuidReturns400() throws Exception {
        Configuration saved = repo.save(new Configuration().setName("A").setKind("ui").setValue("true"));

        mockMvc.perform(
            patch("/configuration/admin/{id}", saved.getUuid()).header(GatewayUserResolver.HEADER_USER_ID, "auth0|abc")
                .header(GatewayUserResolver.HEADER_USER_PRIVILEGES, "SUPER_ADMIN").contentType(MediaType.APPLICATION_JSON)
                .content("{\"uuid\":\"" + UUID.randomUUID() + "\",\"value\":\"false\"}")
        ).andExpect(status().isBadRequest());
    }

    @Test
    void adminUpdateMissingReturns404() throws Exception {
        mockMvc.perform(
            patch("/configuration/admin/{id}", UUID.randomUUID()).header(GatewayUserResolver.HEADER_USER_ID, "auth0|abc")
                .header(GatewayUserResolver.HEADER_USER_PRIVILEGES, "SUPER_ADMIN").contentType(MediaType.APPLICATION_JSON)
                .content("{\"value\":\"false\"}")
        ).andExpect(status().isNotFound());
    }

    @Test
    void adminDeleteWithoutSuperAdminIsForbidden() throws Exception {
        Configuration saved = repo.save(new Configuration().setName("A").setKind("ui").setValue("true"));

        mockMvc.perform(delete("/configuration/admin/{id}", saved.getUuid())).andExpect(status().isForbidden());
    }

    @Test
    void adminDeleteAsSuperAdminReturns200WithDeletedBody() throws Exception {
        Configuration saved = repo.save(new Configuration().setName("A").setKind("ui").setValue("true"));

        mockMvc.perform(
            delete("/configuration/admin/{id}", saved.getUuid()).header(GatewayUserResolver.HEADER_USER_ID, "auth0|abc")
                .header(GatewayUserResolver.HEADER_USER_PRIVILEGES, "SUPER_ADMIN")
        ).andExpect(status().isOk()).andExpect(jsonPath("$.name").value("A"));
    }
}
