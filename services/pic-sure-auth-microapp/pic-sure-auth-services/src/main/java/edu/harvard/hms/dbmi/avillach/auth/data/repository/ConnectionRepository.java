package edu.harvard.hms.dbmi.avillach.auth.data.repository;

import edu.harvard.dbmi.avillach.data.repository.BaseRepository;
import edu.harvard.hms.dbmi.avillach.auth.data.entity.Connection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.enterprise.context.ApplicationScoped;
import javax.persistence.NoResultException;
import javax.persistence.criteria.CriteriaBuilder;
import javax.persistence.criteria.CriteriaQuery;
import javax.persistence.criteria.Root;
import javax.transaction.Transactional;
import java.util.UUID;

@Transactional
@ApplicationScoped
public class ConnectionRepository extends BaseRepository<Connection, UUID> {

    private Logger logger = LoggerFactory.getLogger(ConnectionRepository.class);

    protected ConnectionRepository() {
        super(Connection.class);
    }

    public Connection findConnectionById(String connectionId) {
        CriteriaQuery<Connection> query = em.getCriteriaBuilder().createQuery(Connection.class);
        Root<Connection> queryRoot = query.from(Connection.class);
        query.select(queryRoot);
        CriteriaBuilder cb = cb();
        try {
            return em.createQuery(query
                    .where(
                            eq(cb, queryRoot, "id", connectionId)))
                    .getSingleResult();
        } catch (NoResultException e){
            return null;
        }
    }
}
