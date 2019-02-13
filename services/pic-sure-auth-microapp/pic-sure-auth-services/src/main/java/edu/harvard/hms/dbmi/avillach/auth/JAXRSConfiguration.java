package edu.harvard.hms.dbmi.avillach.auth;


import com.fasterxml.jackson.databind.ObjectMapper;
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
import javax.mail.Session;
import javax.ws.rs.ApplicationPath;
import javax.ws.rs.core.Application;
import javax.ws.rs.core.SecurityContext;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static edu.harvard.hms.dbmi.avillach.auth.utils.AuthNaming.AuthRoleNaming.*;

@Startup
@ApplicationPath("auth")
public class JAXRSConfiguration extends Application {

    private Logger logger = LoggerFactory.getLogger(JAXRSConfiguration.class);

    @Resource(mappedName = "java:global/client_id")
    public static String clientId;

    @Resource(mappedName = "java:global/client_secret")
    public static String clientSecret;
    
    @Resource(mappedName = "java:global/clientSecretIsBase64")
    public static String clientSecretIsBase64;

    @Resource(mappedName = "java:global/user_id_claim")
    public static String userIdClaim;

    @Resource(mappedName = "java:global/auth0host")
    public static String auth0host;
    
    @Resource(mappedName = "java:global/tosEnabled")
    public static String tosEnabled;

    @Resource(mappedName = "java:global/systemName")
    public static String systemName;
    
    @Resource(mappedName = "java:global/userActivationTemplatePath")
    public static String userActivationTemplatePath;

    @Resource(lookup = "java:jboss/mail/gmail")
    public static Session mailSession;

    public static String defaultAdminRoleName = "PIC-SURE Top Admin";

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
        initializeDefaultAdminRole();
        logger.info("Finished initializing admin role.");


        logger.info("Auth micro app has been successfully started");

        mailSession.getProperties().put("mail.smtp.ssl.trust", "smtp.gmail.com");

    }

    private void initializeDefaultAdminRole(){

        // make sure system admin and super admin privileges are added in the database
        checkAndAddAdminPrivileges();

        if (checkIfAdminRoleExists()){
            logger.info("Admin role already exists in database, no need for the creation.");
            return;
        }

        logger.info("Didn't find any role contains both " + SYSTEM +
                " and " + SUPER_ADMIN +
                " in database, start to create one.");
        Privilege systemAdmin = privilegeRepo.getByColumn("name", SYSTEM).get(0);
        Privilege superAdmin = privilegeRepo.getByColumn("name", SUPER_ADMIN).get(0);

        Role role = new Role();
        List<Role> roles = roleRepo.getByColumn("name", defaultAdminRoleName);
        boolean isAdminRole = false;
        if (roles != null && !roles.isEmpty()) {
            logger.info("Found a role with default admin name " + defaultAdminRoleName + ", but without proper privileges associated with");
            role = roles.get(0);
            isAdminRole = true;
        }
        role.setDescription("PIC-SURE Auth Micro App Top admin including Admin and super Admin");
        Set<Privilege> privileges = new HashSet<>();
        privileges.add(systemAdmin);
        privileges.add(superAdmin);
        role.setPrivileges(privileges);

        if(isAdminRole){
            roleRepo.merge(role);
            logger.info("Finished updating the admin role, roleId: " + role.getUuid());
        } else {
            role.setName(defaultAdminRoleName);
            roleRepo.persist(role);
            logger.info("Finished creating an admin role, roleId: " + role.getUuid());
        }


    }

    private void checkAndAddAdminPrivileges(){
        logger.info("Checking if system admin and super admin privileges are added");
        List<Privilege> privileges = privilegeRepo.list();
        if (privileges == null)
            privileges = new ArrayList<>();

        Privilege superAdmin = null, systemAdmin = null;

        for (Privilege p : privileges) {
            if (superAdmin != null && systemAdmin != null)
                break;

            if (SUPER_ADMIN.equals(p.getName())){
                superAdmin = p;
                continue;
            }

            if (SYSTEM.equals(p.getName())){
                systemAdmin = p;
                continue;
            }
        }

        if (superAdmin == null){
            logger.info("Adding super admin");
            superAdmin = new Privilege();
            superAdmin.setName(SUPER_ADMIN);
            superAdmin.setDescription("PIC-SURE Auth super admin for managing roles/privileges/application");
            privilegeRepo.persist(superAdmin);
        }

        if (systemAdmin == null) {
            logger.info("Adding system admin");
            systemAdmin = new Privilege();
            systemAdmin.setName(SYSTEM);
            systemAdmin.setDescription("PIC-SURE Auth admin for managing users/connections.");
            privilegeRepo.persist(systemAdmin);
        }
    }

    private boolean checkIfAdminRoleExists(){
        logger.info("Checking if admin role already exists in database");
        List<Role> roles = roleRepo.list();
        if (roles == null || roles.isEmpty()) {
            return false;
        }

        boolean systemAdmin = false, superAdmin = false;

        for (Role role : roles) {
            Set<Privilege> privileges = role.getPrivileges();
            if (privileges == null || privileges.isEmpty())
                continue;

            for (Privilege privilege : privileges) {
                if (SYSTEM.equals(privilege.getName())) {
                    systemAdmin = true;
                } else if (SUPER_ADMIN.equals(privilege.getName())) {
                    superAdmin = true;
                }
            }
        }

        return systemAdmin && superAdmin;
    }

    public static String getPrincipalName(SecurityContext securityContext){
        if (securityContext.getUserPrincipal() == null)
            return "No security context set, ";

        return securityContext.getUserPrincipal().getName();
    }

}
