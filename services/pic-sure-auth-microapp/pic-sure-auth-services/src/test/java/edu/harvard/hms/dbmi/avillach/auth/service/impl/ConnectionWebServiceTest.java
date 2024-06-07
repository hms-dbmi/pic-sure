package edu.harvard.hms.dbmi.avillach.auth.service.impl;

import edu.harvard.hms.dbmi.avillach.auth.entity.Connection;
import edu.harvard.hms.dbmi.avillach.auth.repository.ConnectionRepository;
import org.junit.Before;
import org.junit.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.*;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

public class ConnectionWebServiceTest {

    @Mock
    private ConnectionRepository connectionRepo;

    @InjectMocks
    private ConnectionWebService service;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
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
    public void testAddConnection_Success() {
        Connection conn = createMockConnection(UUID.randomUUID().toString());
        when(connectionRepo.findById(anyString())).thenReturn(Optional.empty());
        when(connectionRepo.saveAll(anyList())).thenReturn(Collections.singletonList(conn));

        List<Connection> result = service.addConnection(Collections.singletonList(conn));
        assertFalse(result.isEmpty());
        assertEquals(1, result.size());
        verify(connectionRepo, times(1)).saveAll(anyList());
    }

    @Test
    public void testAddConnection_FailureDueToNullFields() {
        Connection conn = new Connection(); // missing required fields
        assertThrows(IllegalArgumentException.class, () -> service.addConnection(Collections.singletonList(conn)));
    }

    @Test
    public void testAddConnection_FailureDueToDuplicateId() {
        Connection conn = createMockConnection("123");
        when(connectionRepo.findById("123")).thenReturn(Optional.of(conn));

        assertThrows(IllegalArgumentException.class, () -> service.addConnection(Collections.singletonList(conn)));
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
    public void testUpdateConnections() {
        Connection conn1 = createMockConnection("123");
        when(connectionRepo.saveAll(anyList())).thenReturn(Collections.singletonList(conn1));

        List<Connection> results = service.updateConnections(Collections.singletonList(conn1));
        assertNotNull(results);
        assertEquals(1, results.size());
        assertEquals("123", results.get(0).getId());
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