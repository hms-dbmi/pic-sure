package edu.harvard.hms.dbmi.avillach.auth.service;

import edu.harvard.hms.dbmi.avillach.auth.data.entity.TermsOfService;
import edu.harvard.hms.dbmi.avillach.auth.data.entity.User;
import edu.harvard.hms.dbmi.avillach.auth.data.repository.TermsOfServiceRepository;
import edu.harvard.hms.dbmi.avillach.auth.data.repository.UserRepository;
import edu.harvard.hms.dbmi.avillach.auth.rest.UserService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.UUID;

public class TermsOfServiceService {

    Logger logger = LoggerFactory.getLogger(TermsOfServiceService.class);

    @Inject
    TermsOfServiceRepository termsOfServiceRepo;

    @Inject
    UserRepository userRepo;

    @Inject
    UserService userService;

    public boolean hasUserAcceptedLatest(String userId){
        logger.info("Checking Terms Of Service acceptance for user with id " + userId);
        return userRepo.checkAgainstTOSDate(userId);
    }

    public TermsOfService updateTermsOfService(String html){
        TermsOfService updatedTOS = new TermsOfService();
        updatedTOS.setContent(html);
        termsOfServiceRepo.persist(updatedTOS);
        return termsOfServiceRepo.getLatest();
    }

    public String getLatest(){
        return termsOfServiceRepo.getLatest().getContent();
    }

    public void acceptTermsOfService(String userId){
        logger.info("User " + userId + " accepting TOS");
        User user = userRepo.findBySubject(userId);
        if (user == null){
            throw new RuntimeException("User does not exist");
        }
        user.setAcceptedTOS(new Date());
        List<User> users = Arrays.asList(user);
        userService.updateUser(users);
    }

}
