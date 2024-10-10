package edu.harvard.dbmi.avillach.dictionary.dataset;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Optional;

@Service
public class DatasetService {

    private final DatasetRepository repository;


    @Autowired
    public DatasetService(DatasetRepository repository) {
        this.repository = repository;
    }

    public Optional<Dataset> getDataset(String ref) {
        Map<String, String> meta = repository.getDatasetMeta(ref);
        return repository.getDataset(ref).map(ds -> ds.withMeta(meta));
    }
}
