package edu.harvard.dbmi.avillach.data.repository;

import edu.harvard.dbmi.avillach.data.entity.Query;

import javax.enterprise.context.ApplicationScoped;
import javax.persistence.PersistenceException;
import javax.transaction.Transactional;
import java.util.UUID;

@Transactional
@ApplicationScoped
public class QueryRepository extends BaseRepository<Query, UUID> {

    protected QueryRepository() {
        super(Query.class);
    }

    public Query getQueryUUIDFromCommonAreaUUID(UUID caID) {
        String caIDRegex = "%commonAreaUUID\":\"" + caID + "\"%";
        String query = "SELECT * FROM query WHERE CONVERT(metadata USING utf8) LIKE ?";
        try {
            return (Query) em().createNativeQuery(query, Query.class).setParameter(1, caIDRegex).getSingleResult();
        } catch (PersistenceException ignored) {
            return null;
        }
    }
}
