package edu.harvard.hms.dbmi.avillach.auth.service.impl;

import edu.harvard.hms.dbmi.avillach.auth.entity.Connection;
import edu.harvard.hms.dbmi.avillach.auth.repository.ConnectionRepository;
import edu.harvard.hms.dbmi.avillach.auth.repository.UserMetadataMappingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockitoAnnotations;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ContextConfiguration;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest
@ContextConfiguration(classes = {ConnectionWebService.class})
public class ConnectionWebServiceTest {

    @MockBean
    private ConnectionRepository connectionRepo;

    @MockBean
    private UserMetadataMappingRepository userMetadataMappingRepo;

    @Autowired
    private ConnectionWebService service;

    @BeforeEach
    public void setUp() {
        MockitoAnnotations.openMocks(this);
    }

    private Connection createMockConnection(String id) {
        Connection conn = new Connection();
        conn.setId(id);
        conn.setLabel("Test Label");
        conn.setSubPrefix("Test SubPrefix");
        conn.setRequiredFields(String.valueOf(Arrays.asList("field1", "field2")));
        return conn;
    }

    @Test
    public void testGetConnectionById_Success() {
        Connection conn = createMockConnection("123");
        when(connectionRepo.findById("123")).thenReturn(Optional.of(conn));

        Connection result = service.getConnectionById("123");
        assertNotNull(result);
        assertEquals("123", result.getId());
    }

    @Test
    public void testGetConnectionById_NotFound() {
        when(connectionRepo.findById("123")).thenReturn(Optional.empty());
        assertThrows(IllegalArgumentException.class, () -> service.getConnectionById("123"));
    }

    @Test
    public void testGetAllConnections() {
        Connection conn1 = createMockConnection("123");
        Connection conn2 = createMockConnection("124");
        when(connectionRepo.findAll()).thenReturn(Arrays.asList(conn1, conn2));

        List<Connection> results = service.getAllConnections();
        assertNotNull(results);
        assertEquals(2, results.size());
    }

    @Test
    public void testRemoveConnectionById() {
        doNothing().when(connectionRepo).deleteById("123");
        when(connectionRepo.findAll()).thenReturn(Collections.emptyList());

        List<Connection> results = service.removeConnectionById("123");
        assertTrue(results.isEmpty());
        verify(connectionRepo, times(1)).deleteById("123");
        verify(connectionRepo, times(1)).findAll();
    }

}