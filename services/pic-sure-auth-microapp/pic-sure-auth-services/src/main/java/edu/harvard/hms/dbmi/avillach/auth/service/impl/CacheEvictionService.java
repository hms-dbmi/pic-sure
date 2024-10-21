package edu.harvard.hms.dbmi.avillach.auth.service.impl;

import edu.harvard.hms.dbmi.avillach.auth.entity.User;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class CacheEvictionService {

    private final SessionService sessionService;
    private final UserService userService;
    private final AccessRuleService accessRuleService;

    @Autowired
    public CacheEvictionService(SessionService sessionService, UserService userService, AccessRuleService accessRuleService) {
        this.sessionService = sessionService;
        this.userService = userService;
        this.accessRuleService = accessRuleService;
    }

    public void evictCache(String userSubject) {
        this.sessionService.endSession(userSubject);
        this.userService.evictFromCache(userSubject);
        this.accessRuleService.evictFromMergedAccessRuleCache(userSubject);
        this.accessRuleService.evictFromPreProcessedAccessRules(userSubject);
    }

    public void evictCache(User user) {
        String userSubject = user.getSubject();
        evictCache(userSubject);
    }

}
