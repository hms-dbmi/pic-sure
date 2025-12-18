package edu.harvard.dbmi.avillach.data.repository;

import edu.harvard.dbmi.avillach.data.entity.Configuration;

import javax.enterprise.context.ApplicationScoped;
import javax.transaction.Transactional;
import java.util.UUID;

@Transactional
@ApplicationScoped
public class ConfigurationRepository extends BaseRepository<Configuration, UUID> {
    protected ConfigurationRepository() {
        super(Configuration.class);
    }
}
