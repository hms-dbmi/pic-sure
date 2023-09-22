package edu.harvard.dbmi.avillach.service;

import javax.inject.Inject;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import edu.harvard.dbmi.avillach.data.entity.NamedDataset;
import edu.harvard.dbmi.avillach.data.entity.Query;
import edu.harvard.dbmi.avillach.data.repository.NamedDatasetRepository;
import edu.harvard.dbmi.avillach.data.repository.QueryRepository;
import edu.harvard.dbmi.avillach.data.request.NamedDatasetRequest;
import edu.harvard.dbmi.avillach.util.exception.ProtocolException;

public class NamedDatasetService {
    private final Logger logger = LoggerFactory.getLogger(NamedDatasetService.class);

    @Inject
    NamedDatasetRepository namedDatasetRepo;

    @Inject
    QueryRepository queryRepo;

    public Optional<List<NamedDataset>> getNamedDatasets(String user){
        List<NamedDataset> queries = namedDatasetRepo.getByColumn("user", user);
        return Optional.ofNullable(queries);
    }

    public Optional<NamedDataset> getNamedDatasetById(String user, UUID datasetId){
        NamedDataset dataset = namedDatasetRepo.getById(datasetId);
        if (dataset == null){
            logger.error("named dataset not found with id " + datasetId.toString());
            return Optional.empty();
        }

        if (!dataset.getUser().toString().equals(user)){
            logger.error("named dataset with id " + datasetId.toString() + " not able to be viewed by user " + user);
            return Optional.empty();
        }

        return Optional.of(dataset);
    }

    public Optional<NamedDataset> addNamedDataset(String user, NamedDatasetRequest request){
        UUID queryId = request.getQueryId();
        Query query = queryRepo.getById(queryId);
        if (query == null){
            logger.error(ProtocolException.QUERY_NOT_FOUND + queryId.toString());
            return Optional.empty();
        }

        NamedDataset dataset = new NamedDataset()
            .setName(request.getName())
            .setQuery(query)
            .setUser(user)
            .setArchived(request.getArchived())
            .setMetadata(request.getMetadata());

        try {
            namedDatasetRepo.persist(dataset);
            logger.debug("persisted named dataset with query id " + queryId.toString());
        } catch (Exception exception){
            logger.error("Error persisting named dataset with query id " + queryId.toString(), exception);
            return Optional.empty();
        }

        return Optional.of(dataset);
    }

    public Optional<NamedDataset> updateNamedDataset(String user, UUID datasetId, NamedDatasetRequest request){
        NamedDataset dataset = namedDatasetRepo.getById(datasetId);
        if (dataset == null){
            logger.error("named dataset not found with id " + datasetId.toString());
            return Optional.empty();
        }

        if (!dataset.getUser().equals(user)){
            logger.error("named dataset with id " + datasetId.toString() + " not able to be updated by user " + user);
            return Optional.empty();
        }

        UUID queryId = request.getQueryId();
        if (!dataset.getQuery().getUuid().equals(queryId)){
            Query query = queryRepo.getById(queryId);
            if (query == null){
                logger.error(ProtocolException.QUERY_NOT_FOUND + queryId.toString());
                return Optional.empty();
            }
            dataset.setQuery(query);
        }

        dataset.setName(request.getName())
            .setArchived(request.getArchived())
            .setMetadata(request.getMetadata());

        try {
            namedDatasetRepo.merge(dataset);
            logger.debug("updated named dataset with id " + datasetId.toString() + " and query id " + queryId.toString());
        } catch (Exception exception){
            logger.error("Error updating named dataset with id " + datasetId.toString() + " and query id " + queryId.toString(), exception);
            return Optional.empty();
        }

        return Optional.of(dataset);
    }
}
