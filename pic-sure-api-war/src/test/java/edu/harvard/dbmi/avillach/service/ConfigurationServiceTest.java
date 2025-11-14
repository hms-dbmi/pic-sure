package edu.harvard.dbmi.avillach.service;

import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doThrow;

import java.time.LocalDate;
import java.util.*;
import java.sql.Date;

import org.junit.Test;
import org.junit.runner.RunWith;

import edu.harvard.dbmi.avillach.data.entity.Configuration;
import edu.harvard.dbmi.avillach.data.repository.ConfigurationRepository;
import edu.harvard.dbmi.avillach.data.request.ConfigurationRequest;

@RunWith(MockitoJUnitRunner.class)
public class ConfigurationServiceTest {
    private final String testName = "FEATURE_FLAG_X";
    private final String testKind = "ui";
    private final String testValue = "true";
    private final String testDescription = "This configuration controls feature X";

    @InjectMocks
    private ConfigurationService configurationService = new ConfigurationService();

    @Mock
    private ConfigurationRepository configurationRepository = mock(ConfigurationRepository.class);

    private Configuration makeConfiguration(UUID id) {
        Configuration config = new Configuration();
        config.setUuid(id);
        config.setName(testName);
        config.setKind(testKind);
        config.setValue(testValue);
        config.setDescription(testDescription);
        return config;
    }

    private ConfigurationRequest makeConfigurationRequest() {
        ConfigurationRequest request = new ConfigurationRequest();
        request.setName(testName);
        request.setKind(testKind);
        request.setValue(testValue);
        request.setDescription(testDescription);
        return request;
    }

    @Test
    public void getConfigurations_withKind_success() {
        // Given there are saved configurations in the database with a specific kind
        Configuration config1 = makeConfiguration(UUID.randomUUID());
        Configuration config2 = makeConfiguration(UUID.randomUUID());
        ArrayList<Configuration> configs = new ArrayList<Configuration>();
        configs.add(config1);
        configs.add(config2);
        when(configurationRepository.getByColumn("kind", testKind)).thenReturn(configs);

        // When the request is received
        Optional<List<Configuration>> response = configurationService.getConfigurations(testKind);

        // Then return a non-empty optional with the configurations
        assertTrue(response.isPresent());
        assertEquals("correct number of configurations returned", 2, response.get().size());
    }

    @Test
    public void getConfigurations_withoutKind_success() {
        // Given there are saved configurations in the database
        Configuration config1 = makeConfiguration(UUID.randomUUID());
        Configuration config2 = makeConfiguration(UUID.randomUUID());
        ArrayList<Configuration> configs = new ArrayList<Configuration>();
        configs.add(config1);
        configs.add(config2);
        when(configurationRepository.list()).thenReturn(configs);

        // When the request is received without specifying a kind
        Optional<List<Configuration>> response = configurationService.getConfigurations(null);

        // Then return a non-empty optional with all configurations
        assertTrue(response.isPresent());
        assertEquals("correct number of configurations returned", 2, response.get().size());
    }

    @Test
    public void getConfigurations_noValues() {
        // Given there are no saved configurations in the database
        ArrayList<Configuration> configs = new ArrayList<Configuration>();
        when(configurationRepository.list()).thenReturn(configs);

        // When the request is received
        Optional<List<Configuration>> response = configurationService.getConfigurations(null);

        // Then return a non-empty optional with an empty list
        assertTrue(response.isPresent());
        assertEquals(0, response.get().size());
    }

    @Test
    public void getConfigurationByIdentifier_withUUID_success() {
        // Given there is a saved configuration in the database with this UUID
        UUID configId = UUID.randomUUID();
        Configuration config = makeConfiguration(configId);
        when(configurationRepository.getById(configId)).thenReturn(config);

        // When the request is received with a UUID
        Optional<Configuration> response = configurationService.getConfigurationByIdentifier(configId.toString());

        // Then return a non-empty optional
        assertTrue(response.isPresent());
        assertEquals("correct configuration returned", configId, response.get().getUuid());
    }

    @Test
    public void getConfigurationByIdentifier_withName_success() {
        // Given there is a saved configuration in the database with this name
        Configuration config = makeConfiguration(UUID.randomUUID());
        ArrayList<Configuration> configs = new ArrayList<Configuration>();
        configs.add(config);
        when(configurationRepository.getByColumn("name", testName)).thenReturn(configs);

        // When the request is received with a name
        Optional<Configuration> response = configurationService.getConfigurationByIdentifier(testName);

        // Then return a non-empty optional
        assertTrue(response.isPresent());
        assertEquals("correct configuration returned", testName, response.get().getName());
    }

    @Test
    public void getConfigurationByIdentifier_withUUID_notFound() {
        // Given there is no configuration with this UUID
        UUID configId = UUID.randomUUID();
        when(configurationRepository.getById(configId)).thenReturn(null);

        // When the request is received
        Optional<Configuration> response = configurationService.getConfigurationByIdentifier(configId.toString());

        // Then return an empty optional
        assertTrue(response.isEmpty());
    }

    @Test
    public void getConfigurationByIdentifier_withName_notFound() {
        // Given there is no configuration with this name
        ArrayList<Configuration> configs = new ArrayList<Configuration>();
        when(configurationRepository.getByColumn("name", testName)).thenReturn(configs);

        // When the request is received with a name
        Optional<Configuration> response = configurationService.getConfigurationByIdentifier(testName);

        // Then return an empty optional
        assertTrue(response.isEmpty());
    }

    @Test
    public void getConfigurationByIdentifier_withName_nullResult() {
        // Given the repository returns null for this name
        when(configurationRepository.getByColumn("name", testName)).thenReturn(null);

        // When the request is received with a name
        Optional<Configuration> response = configurationService.getConfigurationByIdentifier(testName);

        // Then return an empty optional
        assertTrue(response.isEmpty());
    }

    @Test
    public void addConfiguration_success() {
        // Given a valid configuration request
        ConfigurationRequest request = makeConfigurationRequest();

        // When the request is received
        Optional<Configuration> response = configurationService.addConfiguration(request);

        // Then return a non-empty optional
        assertTrue(response.isPresent());
        assertEquals("name is saved", testName, response.get().getName());
        assertEquals("kind is saved", testKind, response.get().getKind());
        assertEquals("value is saved", testValue, response.get().getValue());
        assertEquals("description is saved", testDescription, response.get().getDescription());
    }

    @Test
    public void addConfiguration_nameKindCollision() {
        // Given a valid configuration request
        ConfigurationRequest request = makeConfigurationRequest();

        // And there is a second configuration in the database with desired name
        List<Configuration> duplicateNames = new ArrayList<>(List.of(makeConfiguration(UUID.randomUUID())));
        when(configurationRepository.getByColumns(any(), any())).thenReturn(duplicateNames);

        // When the request is received
        Optional<Configuration> response = configurationService.addConfiguration(request);

        // Then return an empty optional
        assertTrue(response.isEmpty());
    }

    @Test
    public void addConfiguration_cannotPersist() {
        // Given there is an error saving to the configuration table
        doThrow(new RuntimeException("Persistence error")).when(configurationRepository).persist(any(Configuration.class));

        // When the request is received
        ConfigurationRequest request = makeConfigurationRequest();
        Optional<Configuration> response = configurationService.addConfiguration(request);

        // Then return an empty optional
        assertTrue(response.isEmpty());
    }

    @Test
    public void updateConfiguration_changeValue_success() {
        // Given there is a saved configuration in the database with this id
        UUID configId = UUID.randomUUID();
        Configuration config = makeConfiguration(configId);
        when(configurationRepository.getById(configId)).thenReturn(config);

        // When the request is received with updated values
        String newValue = "false";
        ConfigurationRequest request = makeConfigurationRequest();
        request.setUuid(configId);
        request.setValue(newValue);
        Optional<Configuration> response = configurationService.updateConfiguration(request);

        // Then return a non-empty optional
        assertTrue(response.isPresent());
        assertEquals("new value is saved", newValue, response.get().getValue());
    }

    @Test
    public void updateConfiguration_changeName_success() {
        // Given there is a saved configuration in the database with this id
        UUID configId = UUID.randomUUID();
        Configuration config = makeConfiguration(configId);
        when(configurationRepository.getById(configId)).thenReturn(config);

        // When the request is received with a new name
        String newName = "FEATURE_FLAG_Y";
        ConfigurationRequest request = makeConfigurationRequest();
        request.setUuid(configId);
        request.setName(newName);
        Optional<Configuration> response = configurationService.updateConfiguration(request);

        // Then return a non-empty optional
        assertTrue(response.isPresent());
        assertEquals("new name is saved", newName, response.get().getName());
    }


    @Test
    public void updateConfiguration_changeDeleteRequestedDate() {
        // Given there is a saved configuration in the database with this id
        UUID configId = UUID.randomUUID();
        Configuration config = makeConfiguration(configId);
        when(configurationRepository.getById(configId)).thenReturn(config);

        // When the request is received with updated delete request date
        ConfigurationRequest request = makeConfigurationRequest();
        request.setUuid(configId);
        request.setDelete(true);
        Optional<Configuration> response = configurationService.updateConfiguration(request);

        // Then return a non-empty optional
        assertTrue(response.isPresent());
        assertEquals("new delete requested date is saved", true, response.get().getDelete());
    }

    @Test
    public void updateConfiguration_changeName_nameKindCollision() {
        // Given there is a saved configuration in the database with this id
        String newName = "FEATURE_FLAG_Y";
        String newKind = "some kind";
        UUID configId = UUID.randomUUID();
        Configuration config = makeConfiguration(UUID.randomUUID());
        when(configurationRepository.getById(configId)).thenReturn(config);

        // And there is a second configuration in the database with desired name and kind
        Configuration config2 = makeConfiguration(UUID.randomUUID());
        config2.setName(newName);
        config2.setKind("some kind");
        List<Configuration> duplicateNames = new ArrayList<>(List.of(config, config2));
        when(configurationRepository.getByColumns(any(), any())).thenReturn(duplicateNames);

        // When the request is received with a new name
        ConfigurationRequest request = makeConfigurationRequest();
        request.setUuid(configId);
        request.setName(newName);
        request.setKind("some other kind");
        Optional<Configuration> response = configurationService.updateConfiguration(request);

        // Then return an empty optional
        assertTrue(response.isEmpty());
    }

    @Test
    public void updateConfiguration_changeKind_success() {
        // Given there is a saved configuration in the database with this id
        UUID configId = UUID.randomUUID();
        Configuration config = makeConfiguration(configId);
        when(configurationRepository.getById(configId)).thenReturn(config);

        // When the request is received with a new kind
        String newKind = "backend";
        ConfigurationRequest request = makeConfigurationRequest();
        request.setUuid(configId);
        request.setKind(newKind);
        Optional<Configuration> response = configurationService.updateConfiguration(request);

        // Then return a non-empty optional
        assertTrue(response.isPresent());
        assertEquals("new kind is saved", newKind, response.get().getKind());
    }

    @Test
    public void updateConfiguration_changeDescription_success() {
        // Given there is a saved configuration in the database with this id
        UUID configId = UUID.randomUUID();
        Configuration config = makeConfiguration(configId);
        when(configurationRepository.getById(configId)).thenReturn(config);

        // When the request is received with a new description
        String newDescription = "Updated description";
        ConfigurationRequest request = makeConfigurationRequest();
        request.setUuid(configId);
        request.setDescription(newDescription);
        Optional<Configuration> response = configurationService.updateConfiguration(request);

        // Then return a non-empty optional
        assertTrue(response.isPresent());
        assertEquals("new description is saved", newDescription, response.get().getDescription());
    }

    @Test
    public void updateConfiguration_noConfigurationWithId() {
        // Given there is no configuration in the database with this id
        UUID configId = UUID.randomUUID();
        when(configurationRepository.getById(configId)).thenReturn(null);

        // When the request is received
        ConfigurationRequest request = makeConfigurationRequest();
        request.setUuid(configId);
        Optional<Configuration> response = configurationService.updateConfiguration(request);

        // Then return an empty optional
        assertTrue(response.isEmpty());
    }

    @Test
    public void updateConfiguration_cannotPersistChanges() {
        // Given there is an error saving to the configuration table
        UUID configId = UUID.randomUUID();
        Configuration config = makeConfiguration(configId);
        when(configurationRepository.getById(configId)).thenReturn(config);
        doThrow(new RuntimeException()).when(configurationRepository).merge(any(Configuration.class));

        // When the request is received
        ConfigurationRequest request = makeConfigurationRequest();
        request.setUuid(configId);
        Optional<Configuration> response = configurationService.updateConfiguration(request);

        // Then return an empty optional
        assertTrue(response.isEmpty());
    }

    @Test
    public void deleteConfiguration_success() {
        // Given there is a saved configuration in the database with this id
        UUID configId = UUID.randomUUID();
        Configuration config = makeConfiguration(configId);
        when(configurationRepository.getById(configId)).thenReturn(config);

        // When the request is received
        Optional<Configuration> response = configurationService.deleteConfiguration(configId);

        // Then return a non-empty optional with the deleted configuration
        assertTrue(response.isPresent());
        assertEquals("correct configuration returned", configId, response.get().getUuid());
    }

    @Test
    public void deleteConfiguration_noConfigurationWithId() {
        // Given there is no configuration in the database with this id
        UUID configId = UUID.randomUUID();
        when(configurationRepository.getById(configId)).thenReturn(null);

        // When the request is received
        Optional<Configuration> response = configurationService.deleteConfiguration(configId);

        // Then return an empty optional
        assertTrue(response.isEmpty());
    }

    @Test
    public void deleteConfiguration_cannotDelete() {
        // Given there is an error deleting from the configuration table
        UUID configId = UUID.randomUUID();
        Configuration config = makeConfiguration(configId);
        when(configurationRepository.getById(configId)).thenReturn(config);
        doThrow(new RuntimeException()).when(configurationRepository).remove(any(Configuration.class));

        // When the request is received
        Optional<Configuration> response = configurationService.deleteConfiguration(configId);

        // Then return an empty optional
        assertTrue(response.isEmpty());
    }
}
