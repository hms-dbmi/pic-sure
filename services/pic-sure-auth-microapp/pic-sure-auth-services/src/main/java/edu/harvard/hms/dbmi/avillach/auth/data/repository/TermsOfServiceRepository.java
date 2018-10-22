package edu.harvard.hms.dbmi.avillach.auth.data.repository;

import edu.harvard.dbmi.avillach.data.repository.BaseRepository;
import edu.harvard.hms.dbmi.avillach.auth.data.entity.TermsOfService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.enterprise.context.ApplicationScoped;
import javax.persistence.criteria.CriteriaBuilder;
import javax.persistence.criteria.CriteriaQuery;
import javax.persistence.criteria.Root;
import javax.transaction.Transactional;
import java.util.UUID;

@Transactional
@ApplicationScoped
public class TermsOfServiceRepository extends BaseRepository<TermsOfService, UUID> {

    private Logger logger = LoggerFactory.getLogger(TermsOfServiceRepository.class);

    protected TermsOfServiceRepository() {
        super(TermsOfService.class);
    }

    public TermsOfService getLatest(){
        CriteriaQuery<TermsOfService> query = cb().createQuery(TermsOfService.class);
        Root<TermsOfService> queryRoot = query.from(TermsOfService.class);
        query.select(queryRoot);
        CriteriaBuilder cb = cb();
        return em.createQuery(query
                .orderBy(cb.desc(queryRoot.get("dateUpdated"))))
                .setMaxResults(1)
                .getSingleResult();
    }
}
