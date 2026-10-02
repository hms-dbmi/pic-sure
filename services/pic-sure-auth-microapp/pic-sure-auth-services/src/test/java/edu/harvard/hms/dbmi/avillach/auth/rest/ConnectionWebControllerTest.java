package edu.harvard.hms.dbmi.avillach.auth.rest;

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
import edu.harvard.hms.dbmi.avillach.auth.service.impl.ConnectionWebService;

/**
 * Each connection endpoint returns exactly the JSON the {@link Connection} entity serialized to, in the same bare or enveloped shape, and
 * its error paths answer the status they always did in the {@code {message, content}} body.
 */
class ConnectionWebControllerTest {

    private final ConnectionWebService connectionWebService = mock(ConnectionWebService.class);
    private final MockMvc mockMvc = FrozenWire.mockMvc(new ConnectionWebController(connectionWebService));

    private static Connection connection(String id) {
        Connection connection = new Connection().setId(id).setLabel("FENCE").setSubPrefix(id + "|")
            .setRequiredFields("[{\"label\":\"Email\",\"id\":\"email\"}]");
        connection.setUuid(UUID.randomUUID());
        return connection;
    }

    private static Connection connectionWithoutOptionalColumns() {
        Connection connection = new Connection().setId("manual-token");
        connection.setUuid(UUID.randomUUID());
        return connection;
    }

    @Test
    void readingOneConnectionReturnsTheEntityJson() throws Exception {
        Connection connection = connection("fence");
        when(connectionWebService.getConnectionById("fence")).thenReturn(connection);

        mockMvc.perform(get("/connection/{connectionId}", "fence")).andExpect(status().isOk())
            .andExpect(content().string(FrozenWire.json(connection)));
    }

    @Test
    void listingConnectionsReturnsABareArrayThatKeepsNullMembers() throws Exception {
        List<Connection> connections = List.of(connection("fence"), connectionWithoutOptionalColumns());
        when(connectionWebService.getAllConnections()).thenReturn(connections);

        mockMvc.perform(get("/connection")).andExpect(status().isOk()).andExpect(content().string(FrozenWire.json(connections)));
    }

    @Test
    void creatingConnectionsReturnsTheEnvelope() throws Exception {
        List<Connection> created = List.of(connection("okta"));
        when(connectionWebService.createFrom(anyList())).thenReturn(created);

        mockMvc
            .perform(
                post("/connection").contentType(MediaType.APPLICATION_JSON)
                    .content("[{\"id\":\"okta\",\"label\":\"Okta\",\"subPrefix\":\"okta|\",\"requiredFields\":\"[]\"}]")
            ).andExpect(status().isOk())
            .andExpect(content().string(FrozenWire.envelope("All connections are added.", FrozenWire.json(created))));
    }

    @Test
    void updatingConnectionsReturnsABareArray() throws Exception {
        List<Connection> updated = List.of(connection("fence"));
        when(connectionWebService.updateFrom(anyList())).thenReturn(updated);

        mockMvc.perform(
            put("/connection").contentType(MediaType.APPLICATION_JSON)
                .content("[{\"uuid\":\"" + updated.getFirst().getUuid() + "\",\"label\":\"FENCE\"}]")
        ).andExpect(status().isOk()).andExpect(content().string(FrozenWire.json(updated)));
    }

    @Test
    void deletingAConnectionReturnsTheRemainingOnesAsABareArray() throws Exception {
        List<Connection> remaining = List.of(connection("fence"));
        when(connectionWebService.removeConnectionById("okta")).thenReturn(remaining);

        mockMvc.perform(delete("/connection/{connectionId}", "okta")).andExpect(status().isOk())
            .andExpect(content().string(FrozenWire.json(remaining)));
    }

    @Test
    void readingAnUnknownConnectionIs400InTheErrorEnvelope() throws Exception {
        when(connectionWebService.getConnectionById("nope")).thenThrow(new IllegalArgumentException("Connection with id nope not found"));

        mockMvc.perform(get("/connection/{connectionId}", "nope")).andExpect(status().isBadRequest())
            .andExpect(content().string("{\"message\":\"Invalid request\",\"content\":\"Connection with id nope not found\"}"));
    }

    @Test
    void creatingAConnectionWhoseIdExistsIs400InTheErrorEnvelope() throws Exception {
        String reason = "Id must be unique, a connection with id fence already exists in the database";
        when(connectionWebService.createFrom(anyList())).thenThrow(new IllegalArgumentException(reason));

        mockMvc
            .perform(
                post("/connection").contentType(MediaType.APPLICATION_JSON)
                    .content("[{\"id\":\"fence\",\"label\":\"FENCE\",\"subPrefix\":\"fence|\",\"requiredFields\":\"[]\"}]")
            ).andExpect(status().isBadRequest())
            .andExpect(content().string("{\"message\":\"Invalid request\",\"content\":\"" + reason + "\"}"));
    }
}
