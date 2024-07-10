package edu.harvard.hms.dbmi.avillach.auth.service.impl;

import edu.harvard.hms.dbmi.avillach.auth.entity.Privilege;
import edu.harvard.hms.dbmi.avillach.auth.entity.Role;
import edu.harvard.hms.dbmi.avillach.auth.entity.User;
import edu.harvard.hms.dbmi.avillach.auth.enums.SecurityRoles;
import edu.harvard.hms.dbmi.avillach.auth.model.CustomUserDetails;
import edu.harvard.hms.dbmi.avillach.auth.repository.PrivilegeRepository;
import edu.harvard.hms.dbmi.avillach.auth.repository.RoleRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

@Service
public class RoleService {

    private final Logger logger = LoggerFactory.getLogger(RoleService.class);
    private final RoleRepository roleRepository;

    private final PrivilegeRepository privilegeRepo;
    private final PrivilegeService privilegeService;

    @Autowired
    protected RoleService(RoleRepository roleRepository, PrivilegeRepository privilegeRepo, PrivilegeService privilegeService) {
        this.roleRepository = roleRepository;
        this.privilegeRepo = privilegeRepo;
        this.privilegeService = privilegeService;
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

    public List<Role> persistAll(List<Role> newRoles) {
        return this.roleRepository.saveAll(newRoles);
    }

    public List<Role> persistAll(Set<Role> newRoles) {
        return this.roleRepository.saveAll(newRoles);
    }

    public Role findByName(String roleName) {
        return this.roleRepository.findByName(roleName);
    }

    public Role createRole(String roleName, String roleDescription) {
        if (roleName.isEmpty()) {
            logger.info("createRole() roleName is empty");
            return null;
        }
        logger.info("getFENCEProfile() New PSAMA role name:{}", roleName);
        Role r;
        // Create the Role in the repository, if it does not exist. Otherwise, add it.
        Role existing_role = findByName(roleName);
        if (existing_role != null) {
            // Role already exists
            logger.info("upsertRole() role already exists");
            r = existing_role;
        } else {
            // This is a new Role
            r = new Role();
            r.setName(roleName);
            r.setDescription(roleDescription);
            // Since this is a new Role, we need to ensure that the
            // corresponding Privilege (with gates) and AccessRule is added.
            r.setPrivileges(privilegeService.addPrivileges(r));
            logger.info("upsertRole() created new role");
        }

        return r;
    }

    /**
     * Insert or Update the User object's list of Roles in the database.
     *
     * @param u               The User object the generated Role will be added to
     * @param roleName        Name of the Role
     * @param roleDescription Description of the Role
     * @return boolean Whether the Role was successfully added to the User or not
     */
    public boolean upsertRole(User u, String roleName, String roleDescription) {
        boolean status = false;

        // Get the User's list of Roles. The first time, this will be an empty Set.
        // This method is called for every Role, and the User's list of Roles will
        // be updated for all subsequent calls.
        try {
            Role r = null;
            // Create the Role in the Servicesitory, if it does not exist. Otherwise, add it.
            Role existing_role = this.getRoleByName(roleName);
            if (existing_role != null) {
                // Role already exists
                logger.info("upsertRole() role already exists");
                r = existing_role;
            } else {
                // This is a new Role
                r = new Role();
                r.setName(roleName);
                r.setDescription(roleDescription);
                // Since this is a new Role, we need to ensure that the
                // corresponding Privilege (with gates) and AccessRule is added.
                r.setPrivileges(privilegeService.addPrivileges(r));
                this.save(r);
                logger.info("upsertRole() created new role");
            }
            if (u != null) {
                u.getRoles().add(r);
            }
            status = true;
        } catch (Exception ex) {
            logger.error("upsertRole() Could not inser/update role {} to Service", roleName, ex);
        }


        logger.debug("upsertRole() finished");
        return status;
    }
}

