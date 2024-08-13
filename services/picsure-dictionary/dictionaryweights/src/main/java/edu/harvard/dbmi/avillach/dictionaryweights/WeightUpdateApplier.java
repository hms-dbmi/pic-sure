package edu.harvard.dbmi.avillach.dictionaryweights;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class WeightUpdateApplier {

    private static final Logger LOG = LoggerFactory.getLogger(WeightUpdateApplier.class);

    @Autowired
    JdbcTemplate template;
    
    public void applyUpdate(String query) {
        LOG.info("Applying query to DB");
        template.update(query);
    }
}
