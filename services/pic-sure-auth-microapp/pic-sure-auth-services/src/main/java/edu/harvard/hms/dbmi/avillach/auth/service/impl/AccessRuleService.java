package edu.harvard.hms.dbmi.avillach.auth.service.impl;

import edu.harvard.hms.dbmi.avillach.auth.entity.AccessRule;
import edu.harvard.hms.dbmi.avillach.auth.repository.AccessRuleRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class AccessRuleService {

    private final AccessRuleRepository accessRuleRepo;

    @Autowired
    protected AccessRuleService(AccessRuleRepository accessRuleRepo) {
        this.accessRuleRepo = accessRuleRepo;
    }


    public Optional<AccessRule> getAccessRuleById(String accessRuleId) {
        return accessRuleRepo.findById(UUID.fromString(accessRuleId));
    }

    public List<AccessRule> getAllAccessRules() {
        return accessRuleRepo.findAll();
    }

    public List<AccessRule> addAccessRule(List<AccessRule> accessRules) {
        accessRules.forEach(accessRule -> {
            if (accessRule.getEvaluateOnlyByGates() == null)
                accessRule.setEvaluateOnlyByGates(false);

            if (accessRule.getCheckMapKeyOnly() == null)
                accessRule.setCheckMapKeyOnly(false);

            if (accessRule.getCheckMapNode() == null)
                accessRule.setCheckMapNode(false);

            if (accessRule.getGateAnyRelation() == null)
                accessRule.setGateAnyRelation(false);
        });

        return this.accessRuleRepo.saveAll(accessRules);
    }

    public List<AccessRule> updateAccessRules(List<AccessRule> accessRules) {
        return this.accessRuleRepo.saveAll(accessRules);
    }

    @Transactional
    public List<AccessRule> removeAccessRuleById(String accessRuleId) {
        this.accessRuleRepo.deleteById(UUID.fromString(accessRuleId));
        return this.accessRuleRepo.findAll();
    }
}
