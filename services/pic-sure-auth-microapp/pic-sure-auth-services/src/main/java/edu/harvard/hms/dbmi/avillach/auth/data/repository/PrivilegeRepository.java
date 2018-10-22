package edu.harvard.hms.dbmi.avillach.auth.data.repository;

import edu.harvard.dbmi.avillach.data.repository.BaseRepository;
import edu.harvard.hms.dbmi.avillach.auth.data.entity.Privilege;
import edu.harvard.hms.dbmi.avillach.auth.data.entity.Role;

import javax.enterprise.context.ApplicationScoped;
import javax.transaction.Transactional;
import java.util.UUID;

@ApplicationScoped
@Transactional
public class PrivilegeRepository extends BaseRepository<Privilege, UUID> {

    protected PrivilegeRepository() {
        super(Privilege.class);
    }
}
