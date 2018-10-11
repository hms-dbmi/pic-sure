package edu.harvard.hms.dbmi.avillach.auth.data.repository;

import edu.harvard.hms.dbmi.avillach.auth.data.entity.UserMetadataMapping;
import edu.harvard.dbmi.avillach.data.repository.BaseRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.enterprise.context.ApplicationScoped;
import javax.persistence.NoResultException;
import javax.persistence.NonUniqueResultException;
import javax.persistence.criteria.CriteriaBuilder;
import javax.persistence.criteria.CriteriaQuery;
import javax.persistence.criteria.Root;
import javax.transaction.Transactional;

import java.util.List;
import java.util.UUID;

@Transactional
@ApplicationScoped
public class UserMetadataMappingRepository extends BaseRepository<UserMetadataMapping, UUID> {

	private Logger logger = LoggerFactory.getLogger(UserMetadataMappingRepository.class);

	protected UserMetadataMappingRepository() {
		super(UserMetadataMapping.class);
	}

	public List<UserMetadataMapping> findByConnection(String subject) {
		CriteriaQuery<UserMetadataMapping> query = em.getCriteriaBuilder().createQuery(UserMetadataMapping.class);
		Root<UserMetadataMapping> queryRoot = query.from(UserMetadataMapping.class);
		query.select(queryRoot);
		CriteriaBuilder cb = cb();
		return em.createQuery(query
				.where(
						eq(cb, queryRoot, "connectionId", subject)))
				.getResultList();
	}
	
}
