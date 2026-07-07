package edu.harvard.dbmi.avillach.dictionary.datasource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.event.EventListener;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;

@Profile("!test")
@Configuration
public class DataSourceVerifier {

    private static final Logger LOG = LoggerFactory.getLogger(DataSourceVerifier.class);

    private final DataSource dataSource;

    @Autowired
    public DataSourceVerifier(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @EventListener(ContextRefreshedEvent.class)
    public void verifyDataSourceConnection() {
        try (Connection connection = dataSource.getConnection()) {
            if (connection != null) {
                LOG.info("Datasource connection verified successfully.");
            }
        } catch (SQLException e) {
            LOG.info("Failed to obtain a connection from the datasource.");
            LOG.debug("Error verifying datasource connection: {}", e.getMessage());
        }
    }

}
