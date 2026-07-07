package edu.harvard.hms.dbmi.avillach.auth.service.impl;

import edu.harvard.hms.dbmi.avillach.auth.entity.TermsOfService;
import edu.harvard.hms.dbmi.avillach.auth.entity.User;
import edu.harvard.hms.dbmi.avillach.auth.repository.TermsOfServiceRepository;
import edu.harvard.hms.dbmi.avillach.auth.repository.UserRepository;
import edu.harvard.hms.dbmi.avillach.auth.rest.TermsOfServiceController;
import jakarta.annotation.Nullable;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * <p>Provides business logic for the TermsOfService endpoint.</p>>
 *
 * @see TermsOfServiceController
 */
@Service
public class TOSService {

    private final static Logger logger = LoggerFactory.getLogger(TOSService.class);


    private final boolean isToSEnabled;

    private final TermsOfServiceRepository termsOfServiceRepo;

    private final UserRepository userRepo;


    @Autowired
    public TOSService(
        TermsOfServiceRepository termsOfServiceRepo, UserRepository userRepo, @Value("${application.tos.enabled}") boolean isToSEnabled
    ) {
        this.termsOfServiceRepo = termsOfServiceRepo;
        this.userRepo = userRepo;
        this.isToSEnabled = isToSEnabled;
    }


    public boolean hasUserAcceptedLatest(String userSubj) {
        logger.info("Checking if user {} has accepted the latest TOS", userSubj);
        // If TOS is not enabled, then the user has accepted it
        if (!isToSEnabled) {
            logger.info("TOS is disabled");
            return true;
        }

        // If there is no TOS, then the user has accepted it
        if (getLatest() == null) {
            logger.info("No TOS found in database");
            return true;
        }

        return checkAgainstTOSDate(userSubj);
    }

    public Optional<TermsOfService> updateTermsOfService(String html) {
        TermsOfService updatedTOS = new TermsOfService();
        updatedTOS.setContent(html);
        termsOfServiceRepo.save(updatedTOS);
        return termsOfServiceRepo.findTopByOrderByDateUpdatedDesc();
    }

    public @Nullable String getLatest() {
        Optional<TermsOfService> termsOfService = termsOfServiceRepo.findTopByOrderByDateUpdatedDesc();
        if (termsOfService.isPresent()) {
            return termsOfService.get().getContent();
        } else {
            logger.info("Terms Of Service disabled: No Terms of Service found in database");
            return null;
        }
    }

    public User acceptTermsOfService(String userSubj) {
        logger.info("User {} accepting TOS", userSubj);
        User user = userRepo.findBySubject(userSubj);
        if (user == null) {
            throw new RuntimeException("User does not exist");
        }
        user.setAcceptedTOS(new Date());
        Optional<TermsOfService> tosDate = termsOfServiceRepo.findTopByOrderByDateUpdatedDesc();
        if (tosDate.isEmpty()) {
            throw new RuntimeException("No Terms of Service found in database");
        }

        String userLogId = !StringUtils.isBlank(user.getEmail()) ? user.getEmail() : user.getGeneralMetadata();
        logger.info("TOS_LOG : User {} accepted the Terms of Service dated {}", userLogId, tosDate.get().getDateUpdated());
        return user;
    }

    private boolean checkAgainstTOSDate(String userSubj) {
        User user = this.userRepo.findBySubject(userSubj);
        if (user == null) {
            return false;
        }

        Date acceptedTOS = user.getAcceptedTOS();
        logger.info("User {} accepted TOS on {}", userSubj, acceptedTOS);
        if (acceptedTOS == null) {
            return false;
        }

        Optional<TermsOfService> latestTOS = this.termsOfServiceRepo.findTopByOrderByDateUpdatedDesc();
        return latestTOS.filter(termsOfService -> acceptedTOS.compareTo(termsOfService.getDateUpdated()) >= 0).isPresent();
    }

}
