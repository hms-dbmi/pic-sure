package edu.harvard.hms.dbmi.avillach.auth.service.impl;

import edu.harvard.hms.dbmi.avillach.auth.entity.Privilege;
import edu.harvard.hms.dbmi.avillach.auth.entity.Role;
import edu.harvard.hms.dbmi.avillach.auth.enums.SecurityRoles;
import edu.harvard.hms.dbmi.avillach.auth.model.CustomUserDetails;
import edu.harvard.hms.dbmi.avillach.auth.repository.PrivilegeRepository;
import edu.harvard.hms.dbmi.avillach.auth.repository.RoleRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.logging.Logger;
import java.util.stream.Collectors;

@Service
public class RoleService {

    private final static Logger logger = Logger.getLogger(RoleService.class.getName());
    private final RoleRepository roleRepository;

    private final PrivilegeRepository privilegeRepo;

    @Autowired
    protected RoleService(RoleRepository roleRepository, PrivilegeRepository privilegeRepo) {
        this.roleRepository = roleRepository;
        this.privilegeRepo = privilegeRepo;
    }

    public Optional<Role> getRoleById(String roleId) {
        return roleRepository.findById(UUID.fromString(roleId));
    }

    public Optional<Role> getRoleById(UUID roleId) {
        return roleRepository.findById(roleId);
    }

    public List<Role> getAllRoles() {
        return roleRepository.findAll();
    }

    @Transactional
    public List<Role> addRoles(List<Role> roles) {
        checkPrivilegeAssociation(roles);
        return roleRepository.saveAll(roles);
    }

    /**
     * check if the privileges under role is in the database or not,
     * then retrieve it from database and attach it to role object
     *
     * @param roles list of roles
     */
    private void checkPrivilegeAssociation(List<Role> roles) throws RuntimeException {
        for (Role role: roles){
            if (role.getPrivileges() != null) {
                Set<Privilege> privileges = new HashSet<>();
                for (Privilege p : role.getPrivileges()) {
                    Optional<Privilege> privilege = privilegeRepo.findById(p.getUuid());
                    if (privilege.isEmpty()) {
                        throw new RuntimeException("Privilege not found - uuid: " + p.getUuid().toString());
                    }
                    privileges.add(privilege.get());
                }
                role.setPrivileges(privileges);
            }
        }

    }

    @Transactional
    public List<Role> updateRoles(List<Role> roles) {
        checkPrivilegeAssociation(roles);
        return roleRepository.saveAll(roles);
    }

    @Transactional
    public Optional<List<Role>> removeRoleById(String roleId) {
        Optional<Role> optionalRole = roleRepository.findById(UUID.fromString(roleId));

        if (optionalRole.isEmpty()) {
            return Optional.empty();
        }

        SecurityContext context = SecurityContextHolder.getContext();
        CustomUserDetails current_user = (CustomUserDetails) context.getAuthentication().getPrincipal();
        Set<String> roleNames = current_user.getUser().getRoles().stream().map(Role::getName).collect(Collectors.toSet());

        if (!roleNames.contains(SecurityRoles.PIC_SURE_TOP_ADMIN.getRole())){
            logger.info("User doesn't have PIC-SURE Top Admin role, can't remove any role");
            return Optional.empty();
        }

        roleRepository.deleteById(optionalRole.get().getUuid());
        return Optional.of(roleRepository.findAll());
    }

    public void addObjectToSet(Set<Role> roles, Role t) {
        // check if the role exists in the database
        Role role = roleRepository.findById(t.getUuid()).orElse(null);
        if (role == null) {
            throw new RuntimeException("Role not found - uuid: " + t.getUuid().toString());
        }

        roles.add(t);
    }

    public Role getRoleByName(String fenceOpenAccessRoleName) {
        return this.roleRepository.findByName(fenceOpenAccessRoleName);
    }

    public Role save(Role r) {
        return this.roleRepository.save(r);
    }

    public Set<Role> getRolesByIds(Set<UUID> roleUuids) {
        return this.roleRepository.findByUuidIn(roleUuids);
    }

    public void persistAll(List<Role> newRoles) {
        this.roleRepository.saveAll(newRoles);
    }

    public Role findByName(String roleName) {
        return this.roleRepository.findByName(roleName);
    }
}

