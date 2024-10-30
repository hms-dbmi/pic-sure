package edu.harvard.hms.dbmi.avillach.auth.service.impl;

import edu.harvard.hms.dbmi.avillach.auth.entity.Connection;
import edu.harvard.hms.dbmi.avillach.auth.entity.UserMetadataMapping;
import edu.harvard.hms.dbmi.avillach.auth.repository.ConnectionRepository;
import edu.harvard.hms.dbmi.avillach.auth.repository.UserMetadataMappingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockitoAnnotations;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ContextConfiguration;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

@SpringBootTest
@ContextConfiguration(classes = {UserMetadataMappingService.class})
public class UserMetadataMappingServiceTest {

    @MockBean
    private UserMetadataMappingRepository userMetadataMappingRepo;

    @MockBean
    private ConnectionRepository connectionRepo;

    @Autowired
    private UserMetadataMappingService userMetadataMappingService;

    @BeforeEach
    public void setUp() {
        MockitoAnnotations.openMocks(this);
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

    @Test
    public void testAddMappings_ConnectionNotFound() {
        Connection connection = new Connection();
        connection.setId("invalidConnectionId");

        UserMetadataMapping mapping = new UserMetadataMapping();
        mapping.setConnection(connection);

        List<UserMetadataMapping> mappings = List.of(mapping);

        when(connectionRepo.findById("invalidConnectionId")).thenReturn(Optional.empty());

        assertThrows(IllegalArgumentException.class, () -> {
            userMetadataMappingService.addMappings(mappings);
        });
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

    @Test
    public void testGetAllMappingsForConnectionById_NotFound() {
        when(connectionRepo.findById("invalidConnectionId")).thenReturn(Optional.empty());

        assertThrows(IllegalArgumentException.class, () -> {
            userMetadataMappingService.getAllMappingsForConnection("invalidConnectionId");
        });
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
