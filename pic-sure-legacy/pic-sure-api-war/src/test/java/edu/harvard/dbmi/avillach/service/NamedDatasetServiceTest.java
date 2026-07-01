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

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.HashMap;
import java.util.Optional;

import org.junit.Test;
import org.junit.runner.RunWith;

import edu.harvard.dbmi.avillach.data.entity.NamedDataset;
import edu.harvard.dbmi.avillach.data.entity.Query;
import edu.harvard.dbmi.avillach.data.repository.NamedDatasetRepository;
import edu.harvard.dbmi.avillach.data.repository.QueryRepository;
import edu.harvard.dbmi.avillach.data.request.NamedDatasetRequest;

@RunWith(MockitoJUnitRunner.class)
public class NamedDatasetServiceTest {
    private String user = "test.user@email.com";
    private String testName = "test name";

    @InjectMocks
    private NamedDatasetService namedDatasetService = new NamedDatasetService();

    @Mock
    private NamedDatasetRepository datasetRepo = mock(NamedDatasetRepository.class);

    @Mock
    private QueryRepository queryRepo = mock(QueryRepository.class);

    private Query makeQuery(UUID id){
        Query query = new Query();
        query.setUuid(id);
        query.setQuery("{}");
        return query;
    }

    private NamedDataset makeNamedDataset(UUID id, Query query){
        NamedDataset dataset = new NamedDataset();
        dataset.setUuid(id);
        dataset.setUser(user);
        dataset.setName(testName);
        dataset.setQuery(query);
        dataset.setArchived(false);
        return dataset;
    }

    private NamedDatasetRequest makeNamedDatasetRequest(UUID queryId){
        NamedDatasetRequest request = new NamedDatasetRequest();
        request.setName(testName);
        request.setQueryId(queryId);
        request.setArchived(false);
        return request;
    }

    @Test
    public void getNamedDataset_success() {
        // Given there is a saved dataset in the database for this user
        Query query = makeQuery(UUID.randomUUID());
        NamedDataset dataset = makeNamedDataset(UUID.randomUUID(), query);
        ArrayList<NamedDataset> datasets = new ArrayList<NamedDataset>();
        datasets.add(dataset);
        when(datasetRepo.getByColumn("user", user)).thenReturn(datasets);

        // When the request is recieved
        Optional<List<NamedDataset>> response = namedDatasetService.getNamedDatasets(user);
        
        // Then return a non-empty optional
        assertTrue(response.isPresent());
    }

    @Test
    public void getNamedDataset_novalues() {
        // Given there is no saved dataset in the database for this user
        ArrayList<NamedDataset> datasets = new ArrayList<NamedDataset>();
        when(datasetRepo.getByColumn("user", user)).thenReturn(datasets);

        // When the request is recieved
        Optional<List<NamedDataset>> response = namedDatasetService.getNamedDatasets(user);

        // Then return a non-empty optional with an empy list
        assertTrue(response.isPresent());
        assertTrue(response.get().size() == 0);
    }

    @Test
    public void getNamedDatasetById_success() {
        // Given there is a saved dataset in the database for this user
        UUID namedDatasetId = UUID.randomUUID();
        Query query = makeQuery(UUID.randomUUID());
        NamedDataset dataset = makeNamedDataset(namedDatasetId, query);
        when(datasetRepo.getById(namedDatasetId)).thenReturn(dataset);

        // When the request is recieved
        Optional<NamedDataset> response = namedDatasetService.getNamedDatasetById(user, namedDatasetId);

        // Then return a non-empty optional
        assertTrue(response.isPresent());
    }

    @Test
    public void getNamedDatasetById_datasetNotFromUser() {
        // Given there is a saved dataset in the database with this id but a different user
        UUID namedDatasetId = UUID.randomUUID();
        Query query = makeQuery(UUID.randomUUID());
        NamedDataset dataset = makeNamedDataset(namedDatasetId, query);
        dataset.setUser("other.user@email.com");
        when(datasetRepo.getById(namedDatasetId)).thenReturn(dataset);

        // When the request is recieved
        Optional<NamedDataset> response = namedDatasetService.getNamedDatasetById(user, namedDatasetId);

        // Then return an empty optional
        assertTrue(response.isEmpty());
    }

    @Test
    public void getNamedDatasetById_noNamedDatasetWithId() {
        // Given there is no saved dataset in the database with this id
        UUID namedDatasetId = UUID.randomUUID();
        when(datasetRepo.getById(namedDatasetId)).thenReturn(null);

        // When the request is recieved
        Optional<NamedDataset> response = namedDatasetService.getNamedDatasetById(user, namedDatasetId);

        // Then return an empty optional
        assertTrue(response.isEmpty());
    }

    @Test
    public void addNamedDataset_success() {
        // Given there is a query in the database
        UUID queryId = UUID.randomUUID();
        Query query = makeQuery(queryId);
        when(queryRepo.getById(queryId)).thenReturn(query);

        // When the request is recieved
        NamedDatasetRequest request = makeNamedDatasetRequest(queryId);
        Optional<NamedDataset> response = namedDatasetService.addNamedDataset(user, request);

        // Then return a non-empty optional
        assertTrue(response.isPresent());
        assertEquals("related user is saved", user, response.get().getUser());
        assertEquals("related name is saved", testName, response.get().getName());
        assertEquals("related query is saved", queryId, response.get().getQuery().getUuid());
    }

    @Test
    public void addNamedDataset_metadataSet_success() {
        // Given there is a query in the database
        UUID queryId = UUID.randomUUID();
        Query query = makeQuery(queryId);
        when(queryRepo.getById(queryId)).thenReturn(query);
        String testKey = "test";
        String testValue = "value";

        // When the request is recieved
        NamedDatasetRequest request = makeNamedDatasetRequest(queryId);
        HashMap<String, Object> metadata = new HashMap<String, Object>();
        metadata.put(testKey, testValue);
        request.setMetadata(metadata);
        Optional<NamedDataset> response = namedDatasetService.addNamedDataset(user, request);

        // Then return a non-empty optional
        assertTrue(response.isPresent());
        assertEquals("related metadata is saved", testValue, response.get().getMetadata().get(testKey));
    }

    @Test
    public void addNamedDataset_noQueryWithID() {
        // Given there is no query in the database with this id
        UUID queryId = UUID.randomUUID();
        when(queryRepo.getById(queryId)).thenReturn(null);

        // When the request is recieved
        NamedDatasetRequest request = makeNamedDatasetRequest(queryId);
        Optional<NamedDataset> response = namedDatasetService.addNamedDataset(user, request);

        // Then return an empty optional
        assertTrue(response.isEmpty());
    }

    @Test
    public void addNamedDataset_cannotPersist() {
        // Given there is an error saving to the named dataset table
        UUID queryId = UUID.randomUUID();
        Query query = makeQuery(queryId);
        when(queryRepo.getById(queryId)).thenReturn(query);
        doThrow(new RuntimeException()).when(datasetRepo).persist(any(NamedDataset.class));

        // When the request is recieved
        NamedDatasetRequest request = makeNamedDatasetRequest(queryId);
        Optional<NamedDataset> response = namedDatasetService.addNamedDataset(user, request);

        // Then return an empty optional
        assertTrue(response.isEmpty());
    }

    @Test
    public void updateNamedDataset_changeName_success() {
        // Given there is a named dataset saved in the database with this id
        UUID queryId = UUID.randomUUID();
        UUID namedDatasetId = UUID.randomUUID();
        Query query = makeQuery(queryId);
        NamedDataset dataset = makeNamedDataset(namedDatasetId, query);
        when(datasetRepo.getById(namedDatasetId)).thenReturn(dataset);

        // When the request is recieved
        String newName = "new name";
        NamedDatasetRequest request = makeNamedDatasetRequest(queryId);
        request.setName(newName);
        Optional<NamedDataset> response = namedDatasetService.updateNamedDataset(user, namedDatasetId, request);

        // Then return a non-empty optional
        assertTrue(response.isPresent());
        assertEquals("new name id is saved", newName, response.get().getName());
    }

    @Test
    public void updateNamedDataset_changeArchiveState_success() {
        // Given there is a named dataset saved in the database with this id
        UUID queryId = UUID.randomUUID();
        UUID namedDatasetId = UUID.randomUUID();
        Query query = makeQuery(queryId);
        NamedDataset dataset = makeNamedDataset(namedDatasetId, query);
        when(datasetRepo.getById(namedDatasetId)).thenReturn(dataset);

        // When the request is recieved
        NamedDatasetRequest request = makeNamedDatasetRequest(queryId);
        request.setArchived(true);
        Optional<NamedDataset> response = namedDatasetService.updateNamedDataset(user, namedDatasetId, request);

        // Then return a non-empty optional
        assertTrue(response.isPresent());
        assertEquals("new archive state is retained", true, response.get().getArchived());
    }

    @Test
    public void updateNamedDataset_changeMetadata_success() {
        // Given there is a named dataset saved in the database with this id
        UUID queryId = UUID.randomUUID();
        UUID namedDatasetId = UUID.randomUUID();
        Query query = makeQuery(queryId);
        NamedDataset dataset = makeNamedDataset(namedDatasetId, query);
        HashMap<String, Object> oldMetadata = new HashMap<String, Object>();
        oldMetadata.put("whatever", "something");
        dataset.setMetadata(oldMetadata);
        when(datasetRepo.getById(namedDatasetId)).thenReturn(dataset);
        String testKey = "test";
        String testValue = "value";

        // When the request is recieved
        NamedDatasetRequest request = makeNamedDatasetRequest(queryId);
        HashMap<String, Object> newMetadata = new HashMap<String, Object>();
        newMetadata.put(testKey, testValue);
        request.setMetadata(newMetadata);
        Optional<NamedDataset> response = namedDatasetService.updateNamedDataset(user, namedDatasetId, request);

        // Then return a non-empty optional
        assertTrue(response.isPresent());
        assertEquals("new metadata is retained", testValue, response.get().getMetadata().get(testKey));
    }

    @Test
    public void updateNamedDataset_changeQueryId_success() {
        // Given there is a named dataset saved in the database with this id and the new query id is in the database
        UUID queryId = UUID.randomUUID();
        UUID namedDatasetId = UUID.randomUUID();
        Query query = makeQuery(queryId);
        NamedDataset dataset = makeNamedDataset(namedDatasetId, query);
        when(datasetRepo.getById(namedDatasetId)).thenReturn(dataset);

        UUID newQueryId = UUID.randomUUID();
        Query newQuery = makeQuery(newQueryId);
        when(queryRepo.getById(newQueryId)).thenReturn(newQuery);

        // When the request is recieved
        NamedDatasetRequest request = makeNamedDatasetRequest(newQueryId);
        Optional<NamedDataset> response = namedDatasetService.updateNamedDataset(user, namedDatasetId, request);

        // Then return a non-empty optional
        assertTrue(response.isPresent());
        assertEquals("new query id is saved", newQueryId, response.get().getQuery().getUuid());
    }
    
    @Test
    public void updateNamedDataset_noNamedDatasetWithId() {
        // Given there is no named dataset in the database with this id
        UUID namedDatasetId = UUID.randomUUID();
        when(datasetRepo.getById(namedDatasetId)).thenReturn(null);

        // When the request is recieved
        NamedDatasetRequest request = makeNamedDatasetRequest(UUID.randomUUID());
        Optional<NamedDataset> response = namedDatasetService.updateNamedDataset(user, namedDatasetId, request);

        // Then return an empty optional
        assertTrue(response.isEmpty());
    }

    @Test
    public void updateNamedDataset_datasetNotFromUser() {
        // Given there is a saved dataset in the database with this id but a different user
        UUID queryId = UUID.randomUUID();
        UUID namedDatasetId = UUID.randomUUID();
        Query query = makeQuery(queryId);
        NamedDataset dataset = makeNamedDataset(namedDatasetId, query);
        dataset.setUser("other.user@email.com");
        when(datasetRepo.getById(namedDatasetId)).thenReturn(dataset);

        // When the request is recieved
        NamedDatasetRequest request = makeNamedDatasetRequest(queryId);
        Optional<NamedDataset> response = namedDatasetService.updateNamedDataset(user, namedDatasetId, request);

        // Then return an empty optional
        assertTrue(response.isEmpty());
    }

    @Test
    public void updateNamedDataset_changeQueryId_noQueryWithID() {
        // Given there is a named dataset saved in the database with this id but no query id as passed in
        UUID queryId = UUID.randomUUID();
        UUID namedDatasetId = UUID.randomUUID();
        Query query = makeQuery(queryId);
        NamedDataset dataset = makeNamedDataset(namedDatasetId, query);
        when(datasetRepo.getById(namedDatasetId)).thenReturn(dataset);

        UUID newQueryId = UUID.randomUUID();
        when(queryRepo.getById(newQueryId)).thenReturn(null);

        // When the request is recieved
        NamedDatasetRequest request = makeNamedDatasetRequest(newQueryId);
        Optional<NamedDataset> response = namedDatasetService.updateNamedDataset(user, namedDatasetId, request);

        // Then return an empty optional
        assertTrue(response.isEmpty());
    }

    @Test
    public void updateNamedDataset_cannotPersistChanges() {
        // Given there is an error saving to the named dataset table
        UUID queryId = UUID.randomUUID();
        UUID namedDatasetId = UUID.randomUUID();
        Query query = makeQuery(queryId);
        NamedDataset dataset = makeNamedDataset(namedDatasetId, query);
        when(datasetRepo.getById(namedDatasetId)).thenReturn(dataset);
        doThrow(new RuntimeException()).when(datasetRepo).merge(any(NamedDataset.class));

        // When the request is recieved
        NamedDatasetRequest request = makeNamedDatasetRequest(queryId);
        Optional<NamedDataset> response = namedDatasetService.updateNamedDataset(user, namedDatasetId, request);

        // Then return an empty optional
        assertTrue(response.isEmpty());
    }
}
