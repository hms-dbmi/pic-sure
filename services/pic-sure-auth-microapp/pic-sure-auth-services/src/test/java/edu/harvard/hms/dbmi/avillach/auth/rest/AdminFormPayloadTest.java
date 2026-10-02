package edu.harvard.hms.dbmi.avillach.auth.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import edu.harvard.hms.dbmi.avillach.auth.entity.AccessRule;
import edu.harvard.hms.dbmi.avillach.auth.entity.Application;
import edu.harvard.hms.dbmi.avillach.auth.entity.Connection;
import edu.harvard.hms.dbmi.avillach.auth.entity.Privilege;
import edu.harvard.hms.dbmi.avillach.auth.entity.Role;
import edu.harvard.hms.dbmi.avillach.auth.entity.User;
import edu.harvard.hms.dbmi.avillach.auth.model.CustomUserDetails;
import edu.harvard.hms.dbmi.avillach.auth.repository.AccessRuleRepository;
import edu.harvard.hms.dbmi.avillach.auth.repository.ApplicationRepository;
import edu.harvard.hms.dbmi.avillach.auth.repository.ConnectionRepository;
import edu.harvard.hms.dbmi.avillach.auth.repository.PrivilegeRepository;
import edu.harvard.hms.dbmi.avillach.auth.repository.RoleRepository;
import edu.harvard.hms.dbmi.avillach.auth.repository.UserRepository;
import edu.harvard.hms.dbmi.avillach.auth.utils.AuthNaming;

/**
 * Posts the request bodies PIC-SURE-Frontend's admin screens send to the PSAMA create and update endpoints and checks they are accepted and
 * saved, then posts bodies carrying fields outside the request records and checks those fields have no effect. Each frontend body is built
 * the way the frontend builds it: the user form sends the whole connection object from {@code GET /connection} and each role as {@code GET
 * /role/{id}} returns it, with every privilege replaced by an empty object (the form maps privileges to unresolved promises, which
 * serialize as {@code {}}); the user table's activate toggle spreads the whole user from {@code GET /user/{id}} back with that connection
 * and those roles; the privilege form sends the application as {@code {"uuid": ...}}; the role form sends privileges as {@code {"uuid":
 * ...}}; the connection form sends its four fields. The context is the one {@code HandlerAuthorizationTest} and {@code OpenApiDocumentTest}
 * boot, so it is shared from the cache; every row a test seeds carries a unique suffix because that in-memory database outlives each test.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.MOCK,
    properties = {"spring.datasource.url=jdbc:h2:mem:psama-openapi;MODE=MySQL;DB_CLOSE_DELAY=-1;NON_KEYWORDS=USER,VALUE,KEY",
        "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa", "spring.datasource.password=",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect", "spring.jpa.hibernate.ddl-auto=create-drop",
        "APPLICATION_CLIENT_SECRET=openapi-test-placeholder-secret", "management.endpoints.web.exposure.include=none"}
)
@AutoConfigureMockMvc
class AdminFormPayloadTest {

    private static final String CONTEXT_PATH = "/auth";
    private static final String STORED_SUBJECT_PREFIX = "test|target-";
    private static final String STORED_TOKEN = "stored-long-term-token";
    private static final String STORED_PASSPORT = "stored-passport";

    private final ObjectMapper json = new ObjectMapper();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private PrivilegeRepository privilegeRepository;

    @Autowired
    private ApplicationRepository applicationRepository;

    @Autowired
    private ConnectionRepository connectionRepository;

    @Autowired
    private AccessRuleRepository accessRuleRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private String suffix;
    private User caller;
    private Application application;
    private Privilege privilege;
    private Role role;
    private Connection connection;
    private User target;

    @BeforeEach
    void seed() {
        suffix = UUID.randomUUID().toString();
        Role callerRole = role(
            "CALLER_ROLE_" + suffix, privilegeNamed(AuthNaming.AuthRoleNaming.SUPER_ADMIN), privilegeNamed(AuthNaming.AuthRoleNaming.ADMIN)
        );
        caller = userRepository.save(user("test|caller-" + suffix, callerRole, null));

        application = new Application();
        application.setName("APP_" + suffix);
        application.setToken("stored-application-token");
        application = applicationRepository.save(application);

        privilege = new Privilege();
        privilege.setName("PRIV_" + suffix);
        privilege.setDescription("seeded privilege");
        privilege.setApplication(application);
        privilege = privilegeRepository.save(privilege);

        role = role("ROLE_" + suffix, privilege);

        connection = connectionRepository.save(
            new Connection().setId("conn-" + suffix).setLabel("Connection " + suffix).setSubPrefix("conn-" + suffix + "|")
                .setRequiredFields("[]")
        );

        User stored = user(STORED_SUBJECT_PREFIX + suffix, role, connection);
        stored.setToken(STORED_TOKEN);
        stored.setPassport(STORED_PASSPORT);
        target = userRepository.save(stored);
    }

    @Test
    void userFormCreateIsAccepted() throws Exception {
        String email = "new-" + suffix + "@example.org";
        ArrayNode body = json.createArrayNode().add(userFormBody(email, true));

        mockMvc.perform(asAdmin(HttpMethod.POST, "/user").content(body.toString())).andExpect(status().isOk());

        User created = userRepository.findAll().stream().filter(u -> email.equals(u.getEmail())).findFirst().orElseThrow();
        assertThat(created.getConnection().getUuid()).isEqualTo(connection.getUuid());
        assertThat(created.getRoles()).extracting(Role::getUuid).containsExactly(role.getUuid());
        assertThat(created.isActive()).isTrue();
    }

    @Test
    void userFormEditIsAccepted() throws Exception {
        ObjectNode edited = userFormBody(target.getEmail(), false);
        edited.put("uuid", target.getUuid().toString());

        mockMvc.perform(asAdmin(HttpMethod.PUT, "/user").content(json.createArrayNode().add(edited).toString())).andExpect(status().isOk());

        User saved = userRepository.findById(target.getUuid()).orElseThrow();
        assertThat(saved.isActive()).isFalse();
        assertThat(saved.getConnection().getUuid()).isEqualTo(connection.getUuid());
        assertThat(saved.getRoles()).extracting(Role::getUuid).containsExactly(role.getUuid());
    }

    @Test
    void userTableDeactivateIsAccepted() throws Exception {
        ObjectNode spread = (ObjectNode) getJson("/user/{id}", target.getUuid());
        spread.put("active", false);
        spread.set("connection", connectionAsListed());
        spread.putArray("roles").add(roleAsUserFormSendsIt());

        mockMvc.perform(asAdmin(HttpMethod.PUT, "/user").content(json.createArrayNode().add(spread).toString())).andExpect(status().isOk());

        User saved = userRepository.findById(target.getUuid()).orElseThrow();
        assertThat(saved.isActive()).isFalse();
        assertThat(saved.getSubject()).isEqualTo(STORED_SUBJECT_PREFIX + suffix);
        assertThat(saved.getToken()).isEqualTo(STORED_TOKEN);
        assertThat(saved.getPassport()).isEqualTo(STORED_PASSPORT);
    }

    @Test
    void privilegeFormCreateAndEditAreAccepted() throws Exception {
        String name = "PRIV_FORM_" + suffix;
        ObjectNode created = json.createObjectNode().put("name", name).put("description", "from the privilege form");
        created.putObject("application").put("uuid", application.getUuid().toString());

        mockMvc.perform(asAdmin(HttpMethod.POST, "/privilege").content(json.createArrayNode().add(created).toString()))
            .andExpect(status().isOk()).andExpect(jsonPath("$[0].uuid").isString()).andExpect(jsonPath("$[0].name").value(name))
            .andExpect(jsonPath("$[0].application.uuid").value(application.getUuid().toString()))
            .andExpect(jsonPath("$[0].application.token").doesNotExist());

        Privilege stored = privilegeRepository.findByName(name);
        assertThat(stored.getApplication().getUuid()).isEqualTo(application.getUuid());

        ObjectNode edited = json.createObjectNode().put("name", name).put("description", "edited in the privilege form");
        edited.putObject("application").put("uuid", application.getUuid().toString());
        edited.put("uuid", stored.getUuid().toString());

        mockMvc.perform(asAdmin(HttpMethod.PUT, "/privilege").content(json.createArrayNode().add(edited).toString()))
            .andExpect(status().isOk());

        assertThat(privilegeRepository.findById(stored.getUuid()).orElseThrow().getDescription()).isEqualTo("edited in the privilege form");
    }

    @Test
    void privilegeReadIsTheStoredEntityJsonWithoutTheMergedMembers() throws Exception {
        AccessRule rule = new AccessRule();
        rule.setName("AR_PRIV_READ_" + suffix);
        rule.setType(AccessRule.TypeNaming.ALL_EQUALS);
        rule = accessRuleRepository.save(rule);
        privilege.setAccessRules(new HashSet<>(Set.of(rule)));
        privilege = privilegeRepository.save(privilege);
        String expected = new TransactionTemplate(transactionManager)
            .execute(status -> FrozenWire.json(privilegeRepository.findById(privilege.getUuid()).orElseThrow()));

        String body = mockMvc.perform(asAdmin(HttpMethod.GET, "/privilege/{id}", privilege.getUuid())).andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();

        assertThat(body).isEqualTo(expected);
        assertThat(body).contains(rule.getUuid().toString(), application.getUuid().toString())
            .doesNotContain("stored-application-token", "mergedValues", "mergedName");
    }

    @Test
    void roleFormCreateAndEditAreAccepted() throws Exception {
        String name = "ROLE_FORM_" + suffix;
        ObjectNode created = json.createObjectNode().put("name", name).put("description", "from the role form");
        created.putArray("privileges").addObject().put("uuid", privilege.getUuid().toString());

        mockMvc.perform(asAdmin(HttpMethod.POST, "/role").content(json.createArrayNode().add(created).toString()))
            .andExpect(status().isOk());

        Role stored = roleRepository.findByName(name);
        assertThat(stored.getPrivileges()).extracting(Privilege::getUuid).containsExactly(privilege.getUuid());

        ObjectNode edited = json.createObjectNode().put("name", name).put("description", "edited in the role form");
        edited.putArray("privileges").addObject().put("uuid", privilege.getUuid().toString());
        edited.put("uuid", stored.getUuid().toString());

        mockMvc.perform(asAdmin(HttpMethod.PUT, "/role").content(json.createArrayNode().add(edited).toString())).andExpect(status().isOk());

        assertThat(roleRepository.findById(stored.getUuid()).orElseThrow().getDescription()).isEqualTo("edited in the role form");
    }

    @Test
    void connectionFormCreateAndEditAreAccepted() throws Exception {
        String id = "conn-form-" + suffix;
        ObjectNode created =
            json.createObjectNode().put("id", id).put("label", "Form " + suffix).put("requiredFields", "[]").put("subPrefix", id + "|");

        mockMvc.perform(asAdmin(HttpMethod.POST, "/connection").content(json.createArrayNode().add(created).toString()))
            .andExpect(status().isOk()).andExpect(jsonPath("$.message").value("All connections are added."))
            .andExpect(jsonPath("$.content[0].id").value(id)).andExpect(jsonPath("$.content[0].uuid").isString());

        Connection stored = connectionRepository.findById(id).orElseThrow();

        ObjectNode edited = json.createObjectNode().put("id", id).put("label", "Edited " + suffix)
            .put("requiredFields", "[{\"label\":\"Email\",\"id\":\"email\"}]").put("subPrefix", id + "|");
        edited.put("uuid", stored.getUuid().toString());

        mockMvc.perform(asAdmin(HttpMethod.PUT, "/connection").content(json.createArrayNode().add(edited).toString()))
            .andExpect(status().isOk()).andExpect(jsonPath("$[0].uuid").value(stored.getUuid().toString()))
            .andExpect(jsonPath("$[0].label").value("Edited " + suffix))
            .andExpect(jsonPath("$[0].requiredFields").value("[{\"label\":\"Email\",\"id\":\"email\"}]"));

        Connection saved = connectionRepository.findById(id).orElseThrow();
        assertThat(saved.getLabel()).isEqualTo("Edited " + suffix);
        assertThat(saved.getRequiredFields()).isEqualTo("[{\"label\":\"Email\",\"id\":\"email\"}]");
    }

    @Test
    void applicationUpdateCannotReplaceTheToken() throws Exception {
        ObjectNode edited = json.createObjectNode().put("uuid", application.getUuid().toString()).put("name", application.getName())
            .put("description", "edited").put("token", "forged-application-token");

        mockMvc.perform(asAdmin(HttpMethod.PUT, "/application").content(json.createArrayNode().add(edited).toString()))
            .andExpect(status().isOk()).andExpect(jsonPath("$[0].uuid").value(application.getUuid().toString()))
            .andExpect(jsonPath("$[0].description").value("edited"))
            .andExpect(jsonPath("$[0].privileges[0].uuid").value(privilege.getUuid().toString()))
            .andExpect(jsonPath("$[0].token").doesNotExist());

        Application saved = applicationRepository.findById(application.getUuid()).orElseThrow();
        assertThat(saved.getDescription()).isEqualTo("edited");
        assertThat(saved.getToken()).isEqualTo("stored-application-token");
    }

    @Test
    void applicationUpdateIsTheStoredEntityJsonWithoutTheToken() throws Exception {
        ObjectNode edited = json.createObjectNode().put("uuid", application.getUuid().toString()).put("description", "read back");

        String body = mockMvc.perform(asAdmin(HttpMethod.PUT, "/application").content(json.createArrayNode().add(edited).toString()))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        String expected = new TransactionTemplate(transactionManager)
            .execute(status -> FrozenWire.json(List.of(applicationRepository.findById(application.getUuid()).orElseThrow()), "token"));
        assertThat(body).isEqualTo(expected);
        assertThat(body).contains("\"privileges\":[", privilege.getUuid().toString()).doesNotContain("stored-application-token");
    }

    @Test
    void applicationCreateWithoutANameIsRejectedAs400() throws Exception {
        mockMvc.perform(asAdmin(HttpMethod.POST, "/application").content("[{\"description\":\"no name\"}]"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("Invalid request body"))
            .andExpect(jsonPath("$.content").value("[0].name must not be blank"));
    }

    @Test
    void privilegeFormEditKeepsTheAccessRulesThePrivilegeHolds() throws Exception {
        AccessRule rule = new AccessRule();
        rule.setName("AR_" + suffix);
        rule.setType(AccessRule.TypeNaming.ALL_EQUALS);
        rule = accessRuleRepository.save(rule);
        privilege.setAccessRules(new HashSet<>(Set.of(rule)));
        privilege = privilegeRepository.save(privilege);
        ObjectNode edited = json.createObjectNode().put("name", privilege.getName()).put("description", "edited in the privilege form");
        edited.putObject("application").put("uuid", application.getUuid().toString());
        edited.put("uuid", privilege.getUuid().toString());

        mockMvc.perform(asAdmin(HttpMethod.PUT, "/privilege").content(json.createArrayNode().add(edited).toString()))
            .andExpect(status().isOk());

        JsonNode saved = getJson("/privilege/{id}", privilege.getUuid());
        assertThat(saved.path("description").asText()).isEqualTo("edited in the privilege form");
        assertThat(saved.path("accessRules").findValuesAsText("uuid")).containsExactly(rule.getUuid().toString());
    }

    @Test
    void roleCreateCannotOverwriteAnExistingRole() throws Exception {
        String name = "ROLE_OVERWRITE_" + suffix;
        ObjectNode created = json.createObjectNode().put("uuid", role.getUuid().toString()).put("name", name).put("description", "new");
        created.putArray("privileges").addObject().put("uuid", privilege.getUuid().toString());

        mockMvc.perform(asAdmin(HttpMethod.POST, "/role").content(json.createArrayNode().add(created).toString()))
            .andExpect(status().isOk());

        assertThat(roleRepository.findById(role.getUuid()).orElseThrow().getName()).isEqualTo("ROLE_" + suffix);
        assertThat(roleRepository.findByName(name).getUuid()).isNotEqualTo(role.getUuid());
    }

    @Test
    void userUpdateIgnoresFieldsTheLoginFlowsOwn() throws Exception {
        ObjectNode edited = userFormBody(target.getEmail(), true);
        edited.put("uuid", target.getUuid().toString());
        edited.put("subject", "attacker|forged-" + suffix);
        edited.put("token", "forged-long-term-token");
        edited.put("passport", "forged-passport");
        edited.put("auth0metadata", "{\"forged\":true}");
        edited.put("matched", true);
        edited.put("acceptedTOS", 1_700_000_000_000L);

        mockMvc.perform(asAdmin(HttpMethod.PUT, "/user").content(json.createArrayNode().add(edited).toString())).andExpect(status().isOk());

        User saved = userRepository.findById(target.getUuid()).orElseThrow();
        assertThat(saved.getSubject()).isEqualTo(STORED_SUBJECT_PREFIX + suffix);
        assertThat(saved.getToken()).isEqualTo(STORED_TOKEN);
        assertThat(saved.getPassport()).isEqualTo(STORED_PASSPORT);
        assertThat(saved.getAuth0metadata()).isNull();
        assertThat(saved.isMatched()).isFalse();
        assertThat(saved.getAcceptedTOS()).isNull();
    }

    @Test
    void userCreateCannotOverwriteAnExistingUser() throws Exception {
        String email = "overwrite-" + suffix + "@example.org";
        ObjectNode created = userFormBody(email, true);
        created.put("uuid", target.getUuid().toString());
        created.put("subject", "attacker|forged-" + suffix);

        mockMvc.perform(asAdmin(HttpMethod.POST, "/user").content(json.createArrayNode().add(created).toString()))
            .andExpect(status().isOk());

        User untouched = userRepository.findById(target.getUuid()).orElseThrow();
        assertThat(untouched.getEmail()).isEqualTo(target.getEmail());
        assertThat(untouched.getSubject()).isEqualTo(STORED_SUBJECT_PREFIX + suffix);
        User newUser = userRepository.findAll().stream().filter(u -> email.equals(u.getEmail())).findFirst().orElseThrow();
        assertThat(newUser.getUuid()).isNotEqualTo(target.getUuid());
        assertThat(newUser.getSubject()).isNull();
    }

    @Test
    void nullListElementIsRejectedAs400() throws Exception {
        mockMvc.perform(asAdmin(HttpMethod.PUT, "/role").content("[null]")).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value("Invalid request body")).andExpect(jsonPath("$.content").value("[0] must not be null"));
    }

    /**
     * The body {@code UserForm.svelte} builds: the email, the whole connection, general metadata carrying the email, the active flag, and
     * the selected roles as {@code roleAsUserFormSendsIt} describes.
     */
    private ObjectNode userFormBody(String email, boolean active) throws Exception {
        ObjectNode user = json.createObjectNode();
        user.put("email", email);
        user.set("connection", connectionAsListed());
        user.put("generalMetadata", json.createObjectNode().put("email", email).toString());
        user.put("active", active);
        user.putArray("roles").add(roleAsUserFormSendsIt());
        return user;
    }

    /** The seeded connection as {@code GET /connection} lists it, which is what the frontend's connection store holds. */
    private JsonNode connectionAsListed() throws Exception {
        for (JsonNode listed : getJson("/connection")) {
            if (connection.getUuid().toString().equals(listed.path("uuid").asText())) {
                return listed;
            }
        }
        throw new AssertionError("GET /connection does not list the seeded connection");
    }

    /**
     * The seeded role as {@code GET /role/{id}} returns it, with each privilege replaced by the empty object an unresolved promise becomes.
     */
    private ObjectNode roleAsUserFormSendsIt() throws Exception {
        ObjectNode fetched = (ObjectNode) getJson("/role/{id}", role.getUuid());
        ArrayNode unresolved = json.createArrayNode();
        fetched.path("privileges").forEach(ignored -> unresolved.addObject());
        fetched.set("privileges", unresolved);
        return fetched;
    }

    private JsonNode getJson(String path, Object... uriVariables) throws Exception {
        String body = mockMvc.perform(asAdmin(HttpMethod.GET, path, uriVariables)).andExpect(status().isOk()).andReturn().getResponse()
            .getContentAsString();
        return json.readTree(body);
    }

    private MockHttpServletRequestBuilder asAdmin(HttpMethod method, String path, Object... uriVariables) {
        CustomUserDetails details = new CustomUserDetails(caller);
        return MockMvcRequestBuilders.request(method, CONTEXT_PATH + path, uriVariables).contextPath(CONTEXT_PATH)
            .contentType(MediaType.APPLICATION_JSON)
            .with(authentication(new UsernamePasswordAuthenticationToken(details, null, details.getAuthorities())));
    }

    private Privilege privilegeNamed(String name) {
        Privilege existing = privilegeRepository.findByName(name);
        if (existing != null) {
            return existing;
        }
        Privilege created = new Privilege();
        created.setName(name);
        return privilegeRepository.save(created);
    }

    private Role role(String name, Privilege... privileges) {
        Role created = new Role();
        created.setName(name);
        created.setPrivileges(new HashSet<>(List.of(privileges)));
        return roleRepository.save(created);
    }

    private static User user(String subject, Role role, Connection connection) {
        User user = new User().setSubject(subject).setRoles(new HashSet<>(Set.of(role))).setConnection(connection);
        user.setEmail(subject.replace('|', '-') + "@example.org");
        user.setGeneralMetadata("{\"email\":\"" + user.getEmail() + "\"}");
        return user;
    }
}
