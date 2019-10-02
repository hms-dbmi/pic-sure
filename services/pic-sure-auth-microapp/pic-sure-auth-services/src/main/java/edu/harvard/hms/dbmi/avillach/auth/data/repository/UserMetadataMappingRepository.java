package edu.harvard.hms.dbmi.avillach.auth.data.repository;

import edu.harvard.dbmi.avillach.data.repository.BaseRepository;
import edu.harvard.hms.dbmi.avillach.auth.data.entity.Connection;
import edu.harvard.hms.dbmi.avillach.auth.data.entity.UserMetadataMapping;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import javax.persistence.criteria.CriteriaBuilder;
import javax.persistence.criteria.CriteriaQuery;
import javax.persistence.criteria.Join;
import javax.persistence.criteria.Root;
import javax.transaction.Transactional;
import java.util.List;
import java.util.UUID;

/**
 * <p>Provides operations for the UserMetadataMapping entity to interact with a database.</p>
 * @see UserMetadataMapping
 */
@Transactional
@ApplicationScoped
public class UserMetadataMappingRepository extends BaseRepository<UserMetadataMapping, UUID> {

	@Inject
	UserMetadataMappingRepository userMetadataMappingRepository;

	private Logger logger = LoggerFactory.getLogger(UserMetadataMappingRepository.class);

	protected UserMetadataMappingRepository() {
		super(UserMetadataMapping.class);
	}

	public List<UserMetadataMapping> findByConnection(Connection connection) {
		return userMetadataMappingRepository.getByColumn("connection", connection);

//		CriteriaBuilder cb = cb();
//		CriteriaQuery<UserMetadataMapping> query = cb.createQuery(UserMetadataMapping.class);
//		Root<UserMetadataMapping> queryRoot = query.from(UserMetadataMapping.class);
//		query.select(queryRoot);
//		Join connectionJoin = queryRoot.join("connection");
//		return em.createQuery(query
//				.where(
//						cb.equal(connectionJoin.get("uuid"), connectionId)))
//				.getResultList();
	}
	
}
