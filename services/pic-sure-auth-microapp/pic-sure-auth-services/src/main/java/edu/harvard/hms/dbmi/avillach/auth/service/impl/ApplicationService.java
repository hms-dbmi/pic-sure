package edu.harvard.hms.dbmi.avillach.auth.service.impl;

import edu.harvard.hms.dbmi.avillach.auth.entity.Application;
import edu.harvard.hms.dbmi.avillach.auth.entity.Privilege;
import edu.harvard.hms.dbmi.avillach.auth.model.request.ApplicationCreateRequest;
import edu.harvard.hms.dbmi.avillach.auth.model.request.ApplicationUpdateRequest;
import edu.harvard.hms.dbmi.avillach.auth.model.request.EntityIdRef;
import edu.harvard.hms.dbmi.avillach.auth.repository.ApplicationRepository;
import edu.harvard.hms.dbmi.avillach.auth.repository.PrivilegeRepository;
import edu.harvard.hms.dbmi.avillach.auth.utils.AuthNaming;
import edu.harvard.hms.dbmi.avillach.auth.utils.JWTUtil;
import org.hibernate.Hibernate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Service
public class ApplicationService implements UserDetailsService {

    private final static Logger logger = LoggerFactory.getLogger(ApplicationService.class);
    private final ApplicationRepository applicationRepo;
    private final PrivilegeRepository privilegeRepo;
    private final JWTUtil jwtUtil;

    @Autowired
    public ApplicationService(ApplicationRepository applicationRepo, PrivilegeRepository privilegeRepo, JWTUtil jwtUtil) {
        this.applicationRepo = applicationRepo;
        this.privilegeRepo = privilegeRepo;
        this.jwtUtil = jwtUtil;
    }

    /**
     * Retrieves an entity by its ID.
     *
     * @param applicationId the ID of the entity to retrieve
     * @return a ResponseEntity representing the result of the operation
     */
    @Transactional
    public Optional<Application> getApplicationByID(String applicationId) {
        return applicationRepo.findById(UUID.fromString(applicationId));
    }

    /**
     * Retrieves an entity by its ID with its privileges. This method is used to avoid lazy loading exception.
     *
     * @param applicationName
     * @return
     */
    @Transactional
    public Optional<Application> getApplicationByIdWithPrivileges(String applicationName) {
        Optional<Application> byId = this.applicationRepo.findById(UUID.fromString(applicationName));

        if (byId.isEmpty()) {
            return Optional.empty();
        }

        Application application = byId.get();
        Hibernate.initialize(application.getPrivileges());
        return Optional.of(application);
    }

    public List<Application> getAllApplications() {
        return this.applicationRepo.findAll();
    }

    @Transactional
    public List<Application> addNewApplications(List<Application> applications) {
        checkAssociation(applications);
        List<Application> appEntities = this.applicationRepo.saveAll(applications);
        for (Application application : appEntities) {
            application.setToken(generateApplicationToken(application));
        }

        return this.applicationRepo.saveAll(appEntities);
    }

    /**
     * Creates applications from allowlisted request records. The bearer token is minted after the row is persisted, so a request body can
     * neither choose an identifier nor supply a token.
     *
     * @param requests the create requests
     * @return the persisted applications, each with a freshly minted token
     */
    @Transactional
    public List<Application> createFrom(List<ApplicationCreateRequest> requests) {
        List<Application> applications = new ArrayList<>(requests.size());
        for (ApplicationCreateRequest request : requests) {
            Application application = new Application();
            application.setName(request.name());
            application.setDescription(request.description());
            application.setUrl(request.url());
            application.setEnable(request.enable() == null || request.enable());
            attachPrivileges(application, request.privileges());
            applications.add(application);
        }

        List<Application> saved = this.applicationRepo.saveAll(applications);
        for (Application application : saved) {
            application.setToken(generateApplicationToken(application));
        }
        return this.applicationRepo.saveAll(saved);
    }

    /**
     * Applies allowlisted updates to existing applications. {@code token} is absent from the request record, so an update can neither read
     * nor replace the application's bearer token; use {@link #refreshApplicationToken} for that.
     *
     * @param requests the update requests
     * @return the persisted applications
     * @throws IllegalArgumentException if a request names an application that does not exist
     */
    @Transactional
    public List<Application> updateFrom(List<ApplicationUpdateRequest> requests) {
        List<Application> applications = new ArrayList<>(requests.size());
        for (ApplicationUpdateRequest request : requests) {
            Application application = this.applicationRepo.findById(request.uuid())
                .orElseThrow(() -> new IllegalArgumentException("Cannot find application by the given applicationId: " + request.uuid()));
            if (request.name() != null) {
                application.setName(request.name());
            }
            if (request.description() != null) {
                application.setDescription(request.description());
            }
            if (request.url() != null) {
                application.setUrl(request.url());
            }
            if (request.enable() != null) {
                application.setEnable(request.enable());
            }
            if (request.privileges() != null) {
                attachPrivileges(application, request.privileges());
            }
            applications.add(application);
        }
        return this.applicationRepo.saveAll(applications);
    }

    private void attachPrivileges(Application application, Set<EntityIdRef> privilegeRefs) {
        if (privilegeRefs == null) {
            return;
        }
        Set<Privilege> privileges = new HashSet<>();
        for (EntityIdRef ref : privilegeRefs) {
            Privilege privilege = this.privilegeRepo.findById(ref.uuid())
                .orElseThrow(() -> new IllegalArgumentException("Cannot find privilege by input UUID: " + ref.uuid()));
            privilege.setApplication(application);
            privileges.add(privilege);
        }
        application.setPrivileges(privileges);
    }

    @Transactional
    public List<Application> deleteApplicationById(String applicationId) {
        Optional<Application> application = applicationRepo.findById(UUID.fromString(applicationId));

        if (application.isEmpty()) {
            logger.error("deleteApplicationById() cannot find the application by applicationId: {}", applicationId);
            throw new IllegalArgumentException("Cannot find application by the given applicationId: " + applicationId);
        }

        this.applicationRepo.delete(application.get());
        return this.applicationRepo.findAll();
    }

    @Transactional
    public List<Application> updateApplications(List<Application> applications) {
        checkAssociation(applications);
        return this.applicationRepo.saveAll(applications);
    }

    public String refreshApplicationToken(String applicationId) throws NullPointerException, IllegalArgumentException {
        Optional<Application> application = applicationRepo.findById(UUID.fromString(applicationId));

        if (application.isEmpty()) {
            logger.error("refreshApplicationToken() cannot find the application by applicationId: {}", applicationId);
            throw new IllegalArgumentException("Cannot find application by the given applicationId: " + applicationId);
        }

        String newApplicationToken = generateApplicationToken(application.orElse(null));
        if (newApplicationToken == null) {
            logger.error("refreshApplicationToken() failed to generate new application token for applicationId: {}", applicationId);
            throw new NullPointerException("Failed to generate new application token for applicationId: " + applicationId);
        }

        application.get().setToken(newApplicationToken);
        this.applicationRepo.save(application.get());
        return newApplicationToken;
    }

    private void checkAssociation(List<Application> applications) {
        for (Application application : applications) {
            if (application.getPrivileges() != null) {
                Set<Privilege> privileges = new HashSet<>();
                application.getPrivileges().forEach(p -> {
                    Optional<Privilege> optionalPrivilege = privilegeRepo.findById(p.getUuid());
                    if (optionalPrivilege.isPresent()) {
                        Privilege privilege = optionalPrivilege.get();
                        privilege.setApplication(application);
                        privileges.add(privilege);
                    } else {
                        logger.error("Didn't find privilege by uuid: {}", p.getUuid());
                    }
                });

                application.setPrivileges(privileges);
            }
        }
    }

    public String generateApplicationToken(Application application) {
        if (application == null || application.getUuid() == null) {
            logger.error("generateApplicationToken() application is null or uuid is missing to generate the application token");
            throw new NullPointerException("Cannot generate application token, please contact admin");
        }

        return this.jwtUtil.createJwtToken(
            null, null, new HashMap<>(Map.of("user_id", AuthNaming.PSAMA_APPLICATION_TOKEN_PREFIX + "|" + application.getName())),
            AuthNaming.PSAMA_APPLICATION_TOKEN_PREFIX + "|" + application.getUuid().toString(), 365L * 1000 * 60 * 60 * 24
        );
    }

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        return null;
    }

    public Application getApplicationByName(String picsure) {
        return applicationRepo.findByName(picsure);
    }
}

