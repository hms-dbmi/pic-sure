package edu.harvard.hms.dbmi.avillach.auth.data.repository;

import edu.harvard.dbmi.avillach.data.repository.BaseRepository;
import edu.harvard.hms.dbmi.avillach.auth.data.entity.Role;

import javax.enterprise.context.ApplicationScoped;
import javax.transaction.Transactional;
import java.util.UUID;

@ApplicationScoped
@Transactional
public class RoleRepository extends BaseRepository<Role, UUID> {

    protected RoleRepository() {
        super(Role.class);
    }
}
