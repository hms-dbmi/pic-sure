package edu.harvard.hms.dbmi.avillach.auth;


import com.fasterxml.jackson.databind.ObjectMapper;
import edu.harvard.dbmi.avillach.util.PicsureNaming;
import edu.harvard.hms.dbmi.avillach.auth.data.entity.Privilege;
import edu.harvard.hms.dbmi.avillach.auth.data.entity.Role;
import edu.harvard.hms.dbmi.avillach.auth.data.repository.PrivilegeRepository;
import edu.harvard.hms.dbmi.avillach.auth.data.repository.RoleRepository;
import edu.harvard.hms.dbmi.avillach.auth.utils.AuthNaming;
import org.apache.http.client.HttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import javax.ejb.Startup;
import javax.inject.Inject;
import javax.ws.rs.ApplicationPath;
import javax.ws.rs.core.Application;
import javax.ws.rs.core.SecurityContext;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Startup
@ApplicationPath("auth")
public class JAXRSConfiguration extends Application {

    private Logger logger = LoggerFactory.getLogger(JAXRSConfiguration.class);

    @Resource(mappedName = "java:global/client_id")
    public static String clientId;

    @Resource(mappedName = "java:global/client_secret")
    public static String clientSecret;

    @Resource(mappedName = "java:global/user_id_claim")
    public static String userIdClaim;

    public static String defaultAdminRoleName = "PIC-SURE Admin";

    @Inject
    RoleRepository roleRepo;

    @Inject
    PrivilegeRepository privilegeRepo;

    public final static ObjectMapper objectMapper = new ObjectMapper();

    public static final HttpClient client = HttpClientBuilder.create().build();


    @PostConstruct
    public void init() {
        logger.info("Starting auth micro app");

        /*
            create an admin role if there isn't one
         */
        logger.info("Start initializing admin role in database");
        initializeTheAdminRole();
        logger.info("Finished initializing admin role.");


        logger.info("Auth micro app has been successfully started");
    }

    private void initializeTheAdminRole(){

        if (checkIfAdminRoleExists()){
            logger.info("Admin role already exists in database, no need for the creation.");
            return;
        }

        logger.info("Didn't find any admin role exists in database, start to create one.");
        Privilege privilege = new Privilege();
        privilege.setName(AuthNaming.AuthRoleNaming.ROLE_SYSTEM);
        privilege.setDescription("PIC-SURE admin role for modify users and resources.");
        privilegeRepo.persist(privilege);

        Role role = new Role();
        role.setName(defaultAdminRoleName);
        role.setDescription("PIC-SURE admin role across PIC-SURE and PIC-SURE Auth Micro App");
        Set<Privilege> privileges = new HashSet<>();
        privileges.add(privilege);
        role.setPrivileges(privileges);
        roleRepo.persist(role);
        logger.info("Finished creating an admin role, roleId: " + role.getUuid());
    }

    private boolean checkIfAdminRoleExists(){
        logger.info("Checking if admin role already exists in database");
        List<Role> roles = roleRepo.list();
        if (roles == null || roles.isEmpty()) {
            return false;
        }

        boolean b = false;

        for (Role role : roles) {
            Set<Privilege> privileges = role.getPrivileges();
            if (privileges == null || privileges.isEmpty())
                continue;

            for (Privilege privilege : privileges) {
                if (AuthNaming.AuthRoleNaming.ROLE_SYSTEM.equals(privilege.getName())) {
                    b = true;
                    break;
                }
            }
        }

        return b;
    }

    public static String getPrincipalName(SecurityContext securityContext){
        if (securityContext.getUserPrincipal() == null)
            return "No security context set, ";

        return securityContext.getUserPrincipal().getName();
    }

}
