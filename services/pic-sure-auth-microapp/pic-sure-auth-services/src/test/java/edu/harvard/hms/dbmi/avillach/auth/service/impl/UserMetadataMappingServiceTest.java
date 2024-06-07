package edu.harvard.hms.dbmi.avillach.auth.service.impl;

import edu.harvard.hms.dbmi.avillach.auth.entity.Connection;
import edu.harvard.hms.dbmi.avillach.auth.entity.UserMetadataMapping;
import edu.harvard.hms.dbmi.avillach.auth.repository.ConnectionRepository;
import edu.harvard.hms.dbmi.avillach.auth.repository.UserMetadataMappingRepository;
import org.junit.Before;
import org.junit.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class UserMetadataMappingServiceTest {

    @Mock
    private UserMetadataMappingRepository userMetadataMappingRepo;

    @Mock
    private ConnectionRepository connectionRepo;

    @InjectMocks
    private UserMetadataMappingService userMetadataMappingService;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
    }

    @Test
    public void testGetAllMappingsForConnection() {
        Connection connection = new Connection();
        connection.setId("testConnectionId");
        List<UserMetadataMapping> mappings = Arrays.asList(new UserMetadataMapping(), new UserMetadataMapping());

        when(userMetadataMappingRepo.findByConnection(connection)).thenReturn(mappings);

        List<UserMetadataMapping> result = userMetadataMappingService.getAllMappingsForConnection(connection);

        assertEquals(mappings, result);
        verify(userMetadataMappingRepo, times(1)).findByConnection(connection);
    }

    @Test
    public void testAddMappings_Success() {
        Connection connection = new Connection();
        connection.setId("testConnectionId");

        UserMetadataMapping mapping1 = new UserMetadataMapping();
        mapping1.setConnection(connection);
        UserMetadataMapping mapping2 = new UserMetadataMapping();
        mapping2.setConnection(connection);

        List<UserMetadataMapping> mappings = Arrays.asList(mapping1, mapping2);

        when(connectionRepo.findById("testConnectionId")).thenReturn(Optional.of(connection));
        when(userMetadataMappingRepo.saveAll(mappings)).thenReturn(mappings);

        List<UserMetadataMapping> result = userMetadataMappingService.addMappings(mappings);

        assertEquals(mappings, result);
        verify(connectionRepo, times(2)).findById("testConnectionId");
        verify(userMetadataMappingRepo, times(1)).saveAll(mappings);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testAddMappings_ConnectionNotFound() {
        Connection connection = new Connection();
        connection.setId("invalidConnectionId");

        UserMetadataMapping mapping = new UserMetadataMapping();
        mapping.setConnection(connection);

        List<UserMetadataMapping> mappings = List.of(mapping);

        when(connectionRepo.findById("invalidConnectionId")).thenReturn(Optional.empty());

        userMetadataMappingService.addMappings(mappings);
    }

    @Test
    public void testGetAllMappings() {
        List<UserMetadataMapping> mappings = Arrays.asList(new UserMetadataMapping(), new UserMetadataMapping());

        when(userMetadataMappingRepo.findAll()).thenReturn(mappings);

        List<UserMetadataMapping> result = userMetadataMappingService.getAllMappings();

        assertEquals(mappings, result);
        verify(userMetadataMappingRepo, times(1)).findAll();
    }

    @Test
    public void testGetAllMappingsForConnectionById_Success() {
        Connection connection = new Connection();
        connection.setId("testConnectionId");

        when(connectionRepo.findById("testConnectionId")).thenReturn(Optional.of(connection));

        Connection result = userMetadataMappingService.getAllMappingsForConnection("testConnectionId");

        assertEquals(connection, result);
        verify(connectionRepo, times(1)).findById("testConnectionId");
    }

    @Test(expected = IllegalArgumentException.class)
    public void testGetAllMappingsForConnectionById_NotFound() {
        when(connectionRepo.findById("invalidConnectionId")).thenReturn(Optional.empty());

        userMetadataMappingService.getAllMappingsForConnection("invalidConnectionId");
    }

    @Test
    public void testUpdateUserMetadataMappings() {
        List<UserMetadataMapping> mappings = Arrays.asList(new UserMetadataMapping(), new UserMetadataMapping());

        when(userMetadataMappingRepo.saveAll(mappings)).thenReturn(mappings);

        List<UserMetadataMapping> result = userMetadataMappingService.updateUserMetadataMappings(mappings);

        assertEquals(mappings, result);
        verify(userMetadataMappingRepo, times(1)).saveAll(mappings);
    }

    @Test
    public void testRemoveMetadataMappingByIdAndRetrieveAll() {
        UUID mappingId = UUID.randomUUID();
        List<UserMetadataMapping> mappings = Arrays.asList(new UserMetadataMapping(), new UserMetadataMapping());

        doNothing().when(userMetadataMappingRepo).deleteById(mappingId);
        when(userMetadataMappingRepo.findAll()).thenReturn(mappings);

        List<UserMetadataMapping> result = userMetadataMappingService.removeMetadataMappingByIdAndRetrieveAll(mappingId.toString());

        assertEquals(mappings, result);
        verify(userMetadataMappingRepo, times(1)).deleteById(mappingId);
        verify(userMetadataMappingRepo, times(1)).findAll();
    }
}
