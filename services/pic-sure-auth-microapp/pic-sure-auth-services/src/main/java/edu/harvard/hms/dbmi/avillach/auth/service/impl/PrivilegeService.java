package edu.harvard.hms.dbmi.avillach.auth.service.impl;

import edu.harvard.hms.dbmi.avillach.auth.entity.AccessRule;
import edu.harvard.hms.dbmi.avillach.auth.entity.Privilege;
import edu.harvard.hms.dbmi.avillach.auth.repository.PrivilegeRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.ApplicationContextEvent;
import org.springframework.context.event.EventListener;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

import static edu.harvard.hms.dbmi.avillach.auth.utils.AuthNaming.AuthRoleNaming.ADMIN;

@Service
public class PrivilegeService {

    private final static Logger logger = LoggerFactory.getLogger(PrivilegeService.class.getName());

    private final PrivilegeRepository privilegeRepository;
    private final AccessRuleService accessRuleService;

    @Autowired
    protected PrivilegeService(PrivilegeRepository privilegeRepository, AccessRuleService accessRuleService) {
        this.privilegeRepository = privilegeRepository;
        this.accessRuleService = accessRuleService;
    }

    @Transactional
    @EventListener(ApplicationContextEvent.class)
    protected void onContextRefreshedEvent() {
        updateAllPrivilegesOnStartup();
    }

    @Transactional
    public List<Privilege> deletePrivilegeByPrivilegeId(String privilegeId) {
        Optional<Privilege> privilege = this.privilegeRepository.findById(UUID.fromString(privilegeId));

        // Get security context with spring security context
        SecurityContext securityContext = SecurityContextHolder.getContext();
        // Get the principal name from the security context
        String principalName = securityContext.getAuthentication().getName();

        if (ADMIN.equals(privilege.get().getName())) {
            logger.info("User: {}, is trying to remove the system admin privilege: " + ADMIN, principalName);
            throw new RuntimeException(
                "System Admin privilege cannot be removed - uuid: " + privilege.get().getUuid().toString() + ", name: "
                    + privilege.get().getName()
            );
        }

        this.privilegeRepository.deleteById(UUID.fromString(privilegeId));
        return this.getPrivilegesAll();
    }

    public List<Privilege> updatePrivileges(List<Privilege> privileges) {
        this.privilegeRepository.saveAll(privileges);
        return this.getPrivilegesAll();
    }

    public List<Privilege> addPrivileges(List<Privilege> privileges) {
        return this.privilegeRepository.saveAll(privileges);
    }

    public List<Privilege> getPrivilegesAll() {
        return this.privilegeRepository.findAll();
    }

    public Privilege getPrivilegeById(String privilegeId) {
        return this.privilegeRepository.findById(UUID.fromString(privilegeId)).orElse(null);
    }

    public Privilege findByName(String privilegeName) {
        return this.privilegeRepository.findByName(privilegeName);
    }

    public Privilege save(Privilege privilege) {
        return this.privilegeRepository.save(privilege);
    }

    public Optional<Privilege> findById(UUID uuid) {
        return privilegeRepository.findById(uuid);
    }

    /**
     * Attaches the configured standard access rules to every privilege on startup. Standard rules are created by database migration without
     * a privilege attachment, so this is the only thing that binds them to users. This method never removes a standard access rule;
     * removing one requires a migration.
     */
    protected void updateAllPrivilegesOnStartup() {
        List<Privilege> privileges = this.getPrivilegesAll();
        Set<AccessRule> standardAccessRules = this.accessRuleService.addStandardAccessRules();
        if (standardAccessRules.isEmpty()) {
            logger.error("No standard access rules found.");
            return;
        }

        privileges.forEach(privilege -> {
            privilege.getAccessRules().addAll(standardAccessRules);
            this.save(privilege);
        });
    }
}
