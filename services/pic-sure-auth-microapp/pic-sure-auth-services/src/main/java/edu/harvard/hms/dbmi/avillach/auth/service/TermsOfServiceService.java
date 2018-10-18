package edu.harvard.hms.dbmi.avillach.auth.service;

import edu.harvard.hms.dbmi.avillach.auth.data.entity.TermsOfService;
import edu.harvard.hms.dbmi.avillach.auth.data.entity.User;
import edu.harvard.hms.dbmi.avillach.auth.data.repository.TermsOfServiceRepository;
import edu.harvard.hms.dbmi.avillach.auth.data.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import java.util.Date;
import java.util.UUID;

public class TermsOfServiceService {

    Logger logger = LoggerFactory.getLogger(TermsOfServiceService.class);

    @Inject
    TermsOfServiceRepository termsOfServiceRepo;

    @Inject
    UserRepository userRepo;

    public boolean hasUserAcceptedLatest(UUID userId){
        logger.info("Checking Terms Of Service acceptance for user with id " + userId);
        User user = userRepo.getById(userId);
        if (user == null){
            throw new RuntimeException("User does not exist");
        }
        TermsOfService tos = termsOfServiceRepo.getLatest();
        if (user.getAcceptedTOS() == null || user.getAcceptedTOS().before(tos.getDateUpdated())){
            return false;
        }
        return true;
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

    public void acceptTermsOfService(UUID userId){
        logger.info("User with id " + userId + " accepting TOS");
        User user = userRepo.getById(userId);
        if (user == null){
            throw new RuntimeException("User does not exist");
        }
        user.setAcceptedTOS(new Date());
        userRepo.persist(user);
    }

}
