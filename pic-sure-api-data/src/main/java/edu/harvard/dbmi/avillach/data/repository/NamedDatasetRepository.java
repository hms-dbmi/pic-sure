package edu.harvard.dbmi.avillach.data.repository;

import edu.harvard.dbmi.avillach.data.entity.NamedDataset;

import javax.enterprise.context.ApplicationScoped;
import javax.transaction.Transactional;
import java.util.UUID;

@Transactional
@ApplicationScoped
public class NamedDatasetRepository extends BaseRepository<NamedDataset, UUID>{
    protected NamedDatasetRepository() {super(NamedDataset.class);}
}
