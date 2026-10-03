package edu.harvard.hms.dbmi.avillach.auth.rest;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import edu.harvard.hms.dbmi.avillach.auth.entity.User;
import edu.harvard.hms.dbmi.avillach.auth.service.impl.UserService;

/**
 * The four admin user endpoints return the JSON the {@link User} entity serialized to, as a bare object or a bare array, minus the
 * long-term {@code token}, the {@code passport} and {@code auth0metadata}, and minus the merged members of every nested access rule. A
 * failed notification email does not change the shape of a create or update response.
 */
class UserControllerAdminTest {

    private static final String[] DROPPED = {"token", "passport", "auth0metadata"};
    private static final String CREATE_BODY =
        "[{\"email\":\"researcher@example.org\",\"connection\":{\"id\":\"fence\"},\"roles\":[{\"uuid\":\"" + UUID.randomUUID() + "\"}]}]";

    private final UserService userService = mock(UserService.class);
    private final MockMvc mockMvc = FrozenWire.mockMvc(new UserController(userService));

    private static User signedInUser() {
        return AdminFixtures.user(
            "researcher@example.org", AdminFixtures.connection("fence"),
            AdminFixtures.role(
                "PIC-SURE Top Admin",
                AdminFixtures.privilege("SUPER_ADMIN", AdminFixtures.application("PICSURE"), AdminFixtures.accessRule("AR_ONLY_SEARCH"))
            ), AdminFixtures.role("EMPTY_ROLE")
        );
    }

    private static User userWithNothingOptional() {
        User user = new User();
        user.setUuid(UUID.randomUUID());
        return user;
    }

    @Test
    void readingOneUserReturnsTheEntityJsonWithoutTheSecrets() throws Exception {
        User user = signedInUser();
        when(userService.getUserById(user.getUuid().toString())).thenReturn(user);

        mockMvc.perform(get("/user/{userId}", user.getUuid())).andExpect(status().isOk())
            .andExpect(content().string(FrozenWire.json(user, DROPPED))).andExpect(content().string(not(containsString("canary"))));
    }

    @Test
    void listingUsersReturnsABareArrayThatLeavesOutEmptyMembers() throws Exception {
        List<User> users = List.of(signedInUser(), userWithNothingOptional());
        when(userService.getAllUsers()).thenReturn(users);

        mockMvc.perform(get("/user")).andExpect(status().isOk()).andExpect(content().string(FrozenWire.json(users, DROPPED)))
            .andExpect(content().string(not(containsString("canary"))))
            .andExpect(content().string(containsString("{\"uuid\":\"" + users.get(1).getUuid() + "\",\"matched\":false,\"active\":true}")));
    }

    @Test
    void creatingUsersReturnsABareArray() throws Exception {
        List<User> created = List.of(signedInUser());
        when(userService.createFrom(anyList())).thenReturn(created);
        when(userService.sendUserUpdateEmailsFromResponse(created)).thenReturn(null);

        mockMvc.perform(post("/user").contentType(MediaType.APPLICATION_JSON).content(CREATE_BODY)).andExpect(status().isOk())
            .andExpect(content().string(FrozenWire.json(created, DROPPED)));
    }

    @Test
    void updatingUsersReturnsABareArray() throws Exception {
        List<User> updated = List.of(signedInUser());
        when(userService.updateFrom(anyList())).thenReturn(updated);
        when(userService.sendUserUpdateEmailsFromResponse(updated)).thenReturn(null);

        mockMvc.perform(
            put("/user").contentType(MediaType.APPLICATION_JSON)
                .content("[{\"uuid\":\"" + updated.getFirst().getUuid() + "\",\"active\":false}]")
        ).andExpect(status().isOk()).andExpect(content().string(FrozenWire.json(updated, DROPPED)));
    }

    @Test
    void aFailedNotificationEmailStillReturnsTheBareArray() throws Exception {
        List<User> created = List.of(signedInUser());
        when(userService.createFrom(anyList())).thenReturn(created);
        when(userService.sendUserUpdateEmailsFromResponse(created))
            .thenReturn("  WARN - could not send email to user researcher@example.org see logs for more info");

        mockMvc.perform(post("/user").contentType(MediaType.APPLICATION_JSON).content(CREATE_BODY)).andExpect(status().isOk())
            .andExpect(content().string(FrozenWire.json(created, DROPPED)));
    }

    @Test
    void creatingUsersWithoutACallerIs500InTheErrorEnvelope() throws Exception {
        when(userService.createFrom(anyList())).thenReturn(null);

        mockMvc.perform(post("/user").contentType(MediaType.APPLICATION_JSON).content(CREATE_BODY))
            .andExpect(status().isInternalServerError()).andExpect(
                content().string("{\"message\":\"Application error\",\"content\":\"Inner application error, please contact admin.\"}")
            );
    }

    @Test
    void updatingUsersWithoutACallerIs500InTheErrorEnvelope() throws Exception {
        when(userService.updateFrom(anyList())).thenReturn(null);

        mockMvc
            .perform(put("/user").contentType(MediaType.APPLICATION_JSON).content("[{\"uuid\":\"" + UUID.randomUUID() + "\"}]"))
            .andExpect(status().isInternalServerError()).andExpect(
                content().string("{\"message\":\"Application error\",\"content\":\"Inner application error, please contact admin.\"}")
            );
    }

    @Test
    void readingAnUnknownUserIs400InTheErrorEnvelope() throws Exception {
        UUID unknown = UUID.randomUUID();
        when(userService.getUserById(unknown.toString()))
            .thenThrow(new IllegalArgumentException("Cannot find user by input UUID: " + unknown));

        mockMvc.perform(get("/user/{userId}", unknown)).andExpect(status().isBadRequest()).andExpect(
            content().string("{\"message\":\"Invalid request\",\"content\":\"Cannot find user by input UUID: " + unknown + "\"}")
        );
    }
}
