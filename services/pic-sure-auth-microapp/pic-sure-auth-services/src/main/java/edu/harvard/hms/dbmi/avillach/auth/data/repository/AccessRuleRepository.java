package edu.harvard.hms.dbmi.avillach.auth.data.repository;

import edu.harvard.dbmi.avillach.data.repository.BaseRepository;
import edu.harvard.hms.dbmi.avillach.auth.data.entity.AccessRule;

import javax.enterprise.context.ApplicationScoped;
import javax.transaction.Transactional;
import java.util.UUID;

/**
 * <p>Provides operations for the AccessRule entity to interact with a database.</p>
 *
 * @see AccessRule
 */
@ApplicationScoped
@Transactional
public class AccessRuleRepository extends BaseRepository<AccessRule, UUID> {

    protected AccessRuleRepository() {
        super(AccessRule.class);
    }
}
