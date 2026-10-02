package edu.harvard.hms.dbmi.avillach.auth.rest;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
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

import edu.harvard.hms.dbmi.avillach.auth.entity.Connection;
import edu.harvard.hms.dbmi.avillach.auth.entity.UserMetadataMapping;
import edu.harvard.hms.dbmi.avillach.auth.service.impl.UserMetadataMappingService;

/**
 * Each mapping endpoint returns exactly the JSON its entity serialized to, and the two error paths the controller owns keep their 500 in
 * the {@code {message, content}} body. {@code GET /mapping/{connectionId}} returns the connection itself, not its mappings, as it always
 * has.
 */
class UserMetadataMappingWebControllerTest {

    private static final String CREATE_BODY =
        "[{\"connection\":{\"id\":\"fence\"},\"generalMetadataJsonPath\":\"$.email\",\"auth0MetadataJsonPath\":\"$.email\"}]";

    private final UserMetadataMappingService mappingService = mock(UserMetadataMappingService.class);
    private final MockMvc mockMvc = FrozenWire.mockMvc(new UserMetadataMappingWebController(mappingService));

    private static Connection connection() {
        Connection connection = new Connection().setId("fence").setLabel("FENCE").setSubPrefix("fence|").setRequiredFields("[]");
        connection.setUuid(UUID.randomUUID());
        return connection;
    }

    private static UserMetadataMapping mapping() {
        UserMetadataMapping mapping =
            new UserMetadataMapping().setConnection(connection()).setGeneralMetadataJsonPath("$.email").setAuth0MetadataJsonPath("$.email");
        mapping.setUuid(UUID.randomUUID());
        return mapping;
    }

    @Test
    void readingByConnectionReturnsTheConnectionEntityJson() throws Exception {
        Connection connection = connection();
        when(mappingService.getAllMappingsForConnection("fence")).thenReturn(connection);

        mockMvc.perform(get("/mapping/{connectionId}", "fence")).andExpect(status().isOk())
            .andExpect(content().string(FrozenWire.json(connection)));
    }

    private static UserMetadataMapping mappingWithNothingSet() {
        UserMetadataMapping mapping = new UserMetadataMapping();
        mapping.setUuid(UUID.randomUUID());
        return mapping;
    }

    @Test
    void listingMappingsReturnsABareArrayThatKeepsNullMembers() throws Exception {
        List<UserMetadataMapping> mappings = List.of(mapping(), mappingWithNothingSet());
        when(mappingService.getAllMappings()).thenReturn(mappings);

        mockMvc.perform(get("/mapping")).andExpect(status().isOk()).andExpect(content().string(FrozenWire.json(mappings)))
            .andExpect(content().string(containsString("\"connection\":null")));
    }

    @Test
    void creatingMappingsReturnsABareArray() throws Exception {
        List<UserMetadataMapping> created = List.of(mapping());
        when(mappingService.createFrom(anyList())).thenReturn(created);

        mockMvc.perform(post("/mapping").contentType(MediaType.APPLICATION_JSON).content(CREATE_BODY)).andExpect(status().isOk())
            .andExpect(content().string(FrozenWire.json(created)));
    }

    @Test
    void updatingMappingsReturnsABareArray() throws Exception {
        List<UserMetadataMapping> updated = List.of(mapping());
        when(mappingService.updateFrom(anyList())).thenReturn(updated);

        mockMvc.perform(
            put("/mapping").contentType(MediaType.APPLICATION_JSON)
                .content("[{\"uuid\":\"" + updated.getFirst().getUuid() + "\",\"generalMetadataJsonPath\":\"$.email\"}]")
        ).andExpect(status().isOk()).andExpect(content().string(FrozenWire.json(updated)));
    }

    @Test
    void deletingAMappingReturnsTheRemainingOnesAsABareArray() throws Exception {
        UUID deleted = UUID.randomUUID();
        List<UserMetadataMapping> remaining = List.of(mapping());
        when(mappingService.removeMetadataMappingByIdAndRetrieveAll(deleted.toString())).thenReturn(remaining);

        mockMvc.perform(delete("/mapping/{mappingId}", deleted)).andExpect(status().isOk())
            .andExpect(content().string(FrozenWire.json(remaining)));
    }

    @Test
    void creatingAMappingForAnUnknownConnectionIs500InTheErrorEnvelope() throws Exception {
        when(mappingService.createFrom(anyList()))
            .thenThrow(new IllegalArgumentException("The following connectionIds do not exist:\nfence"));

        mockMvc.perform(post("/mapping").contentType(MediaType.APPLICATION_JSON).content(CREATE_BODY))
            .andExpect(status().isInternalServerError()).andExpect(
                content().string("{\"message\":\"Application error\",\"content\":\"The following connectionIds do not exist:\\nfence\"}")
            );
    }

    @Test
    void updatingNoMappingsIs500InTheErrorEnvelope() throws Exception {
        when(mappingService.updateFrom(anyList())).thenReturn(List.of());

        mockMvc.perform(put("/mapping").contentType(MediaType.APPLICATION_JSON).content("[]")).andExpect(status().isInternalServerError())
            .andExpect(
                content().string("{\"message\":\"Application error\",\"content\":\"No UserMetadataMapping found with the given Ids\"}")
            );
    }
}
