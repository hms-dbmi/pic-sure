package edu.harvard.hms.dbmi.avillach.auth.service.impl.authentication;

import edu.harvard.hms.dbmi.avillach.auth.entity.Role;
import edu.harvard.hms.dbmi.avillach.auth.entity.User;
import edu.harvard.hms.dbmi.avillach.auth.service.AuthenticationService;
import edu.harvard.hms.dbmi.avillach.auth.service.impl.RoleService;
import edu.harvard.hms.dbmi.avillach.auth.service.impl.UserService;
import edu.harvard.hms.dbmi.avillach.auth.service.impl.AccessRuleService;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

@Service
public class OpenAuthenticationService implements AuthenticationService {

    private final Logger logger = LoggerFactory.getLogger(OpenAuthenticationService.class);

    private final UserService userService;
    private final RoleService roleService;
    private final AccessRuleService accessRuleService;
    private final boolean isOpenEnabled;

    @Autowired
    public OpenAuthenticationService(UserService userService, RoleService roleService, AccessRuleService accessRuleService,
                                     @Value("${open.idp.provider.is.enabled}") boolean isOpenEnabled) {
        this.userService = userService;
        this.roleService = roleService;
        this.accessRuleService = accessRuleService;
        this.isOpenEnabled = isOpenEnabled;
    }

    @Override
    public HashMap<String, String> authenticate(Map<String, String> authRequest, String host) {
        String userUUID = authRequest.get("UUID");
        User current_user = null;

        // Try to get the user by UUID
        if (StringUtils.isNotBlank(userUUID)) {
            try {
                current_user = userService.findUserByUUID(userUUID);
            } catch (IllegalArgumentException e) {
                logger.error("Invalid UUID: {}", userUUID);
            }
        }

        // If we can't find the user by UUID, create a new one
        if (current_user == null) {
            Role openAccessRole = roleService.getRoleByName(FENCEAuthenticationService.fence_open_access_role_name);
            current_user = userService.createOpenAccessUser(openAccessRole);

            //clear some cache entries if we register a new login
            // I don't see a clear need to caching here.
            accessRuleService.evictFromCache(current_user.getEmail());
            userService.evictFromCache(current_user.getEmail());
        }

        HashMap<String, Object> claims = new HashMap<>();
        claims.put("sub", current_user.getSubject());
        claims.put("email", current_user.getUuid() + "@open_access.com");
        claims.put("uuid", current_user.getUuid().toString());
        HashMap<String, String> responseMap = userService.getUserProfileResponse(claims);

        logger.info("LOGIN SUCCESS ___ {}:{} ___ Authorization will expire at  ___ {}___", current_user.getEmail(), current_user.getUuid().toString(), responseMap.get("expirationDate"));

        return responseMap;
    }

    @Override
    public String getProvider() {
        return "open";
    }

    @Override
    public boolean isEnabled() {
        return this.isOpenEnabled;
    }
}
