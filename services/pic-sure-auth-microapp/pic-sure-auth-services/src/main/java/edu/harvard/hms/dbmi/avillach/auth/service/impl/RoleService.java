package edu.harvard.hms.dbmi.avillach.auth.service.impl;

import edu.harvard.hms.dbmi.avillach.auth.entity.Privilege;
import edu.harvard.hms.dbmi.avillach.auth.entity.Role;
import edu.harvard.hms.dbmi.avillach.auth.model.request.EntityIdRef;
import edu.harvard.hms.dbmi.avillach.auth.model.request.RoleCreateRequest;
import edu.harvard.hms.dbmi.avillach.auth.model.request.RoleUpdateRequest;
import edu.harvard.hms.dbmi.avillach.auth.repository.RoleRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class RoleService {

    private final Logger logger = LoggerFactory.getLogger(RoleService.class);
    private final RoleRepository roleRepository;
    private final PrivilegeService privilegeService;
    public static final String MANAGED_OPEN_ACCESS_ROLE_NAME = "MANUAL_ROLE_OPEN_ACCESS";
    public static final String MANAGED_AUTH_ACCESS_ROLE_NAME = "MANUAL_ROLE_AUTH_ACCESS";
    public static final String MANAGED_ROLE_NAMED_DATASET = "MANUAL_ROLE_NAMED_DATASET";

    @Autowired
    public RoleService(RoleRepository roleRepository, PrivilegeService privilegeService) {
        this.roleRepository = roleRepository;
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
     * Creates roles from allowlisted request records. Privileges are resolved from storage by UUID; the identifier is generated on persist.
     *
     * @param requests the create requests
     * @return the persisted roles
     * @throws IllegalArgumentException if a referenced privilege does not exist
     */
    @Transactional
    public List<Role> createFrom(List<RoleCreateRequest> requests) {
        List<Role> roles = new ArrayList<>(requests.size());
        for (RoleCreateRequest request : requests) {
            Role role = new Role();
            role.setName(request.name());
            role.setDescription(request.description());
            role.setPrivileges(resolvePrivileges(request.privileges()));
            roles.add(role);
        }
        return roleRepository.saveAll(roles);
    }

    /**
     * Applies allowlisted updates to existing roles. A member left out of the request leaves the stored value unchanged.
     *
     * @param requests the update requests
     * @return the persisted roles
     * @throws IllegalArgumentException if the role or a referenced privilege does not exist
     */
    @Transactional
    public List<Role> updateFrom(List<RoleUpdateRequest> requests) {
        List<Role> roles = new ArrayList<>(requests.size());
        for (RoleUpdateRequest request : requests) {
            Role role = roleRepository.findById(request.uuid())
                .orElseThrow(() -> new IllegalArgumentException("Role not found - uuid: " + request.uuid()));
            if (request.name() != null) {
                role.setName(request.name());
            }
            if (request.description() != null) {
                role.setDescription(request.description());
            }
            if (request.privileges() != null) {
                role.setPrivileges(resolvePrivileges(request.privileges()));
            }
            roles.add(role);
        }
        return roleRepository.saveAll(roles);
    }

    private Set<Privilege> resolvePrivileges(Set<EntityIdRef> privilegeRefs) {
        if (privilegeRefs == null) {
            return null;
        }
        Set<Privilege> privileges = new HashSet<>();
        for (EntityIdRef ref : privilegeRefs) {
            privileges.add(
                this.privilegeService.findById(ref.uuid())
                    .orElseThrow(() -> new IllegalArgumentException("Privilege not found - uuid: " + ref.uuid()))
            );
        }
        return privileges;
    }

    /**
     * check if the privileges under role is in the database or not, then retrieve it from database and attach it to role object
     *
     * @param roles list of roles
     */
    private void checkPrivilegeAssociation(List<Role> roles) throws RuntimeException {
        for (Role role : roles) {
            if (role.getPrivileges() != null) {
                Set<Privilege> privileges = new HashSet<>();
                for (Privilege p : role.getPrivileges()) {
                    Optional<Privilege> privilege = this.privilegeService.findById(p.getUuid());
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

    public Role getRoleByName(String roleName) {
        return this.roleRepository.findByName(roleName);
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

    public Role findByName(String roleName) {
        return this.roleRepository.findByName(roleName);
    }

    public Set<Role> findByNameIn(Set<String> roleNames) {
        return this.roleRepository.findByNameIn(roleNames);
    }

    public Map<String, Role> findByNames(Set<String> roleNames) {
        return this.findByNameIn(roleNames).stream().collect(Collectors.toMap(Role::getName, Function.identity()));
    }

}
