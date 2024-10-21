package edu.harvard.hms.dbmi.avillach.auth.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import edu.harvard.hms.dbmi.avillach.auth.entity.User;
import edu.harvard.hms.dbmi.avillach.auth.enums.PassportValidationResponse;
import edu.harvard.hms.dbmi.avillach.auth.model.ras.Ga4ghPassportV1;
import edu.harvard.hms.dbmi.avillach.auth.model.ras.Passport;
import edu.harvard.hms.dbmi.avillach.auth.model.ras.RasDbgapPermission;
import edu.harvard.hms.dbmi.avillach.auth.utils.JWTUtil;
import edu.harvard.hms.dbmi.avillach.auth.utils.RestClientUtil;
import jakarta.annotation.PostConstruct;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Service
public class RASPassPortService {

    private final Logger logger = LoggerFactory.getLogger(RASPassPortService.class);
    private final RestClientUtil restClientUtil;
    private final UserService userService;
    private final String rasURI;
    private final CacheEvictionService cacheEvictionService;

    @Autowired
    public RASPassPortService(RestClientUtil restClientUtil,
                              UserService userService,
                              @Value("${ras.idp.uri}") String rasURI, CacheEvictionService cacheEvictionService) {
        this.restClientUtil = restClientUtil;
        this.userService = userService;
        this.rasURI = rasURI.replaceAll("/$", "");
        this.cacheEvictionService = cacheEvictionService;

        logger.info("RASPassPortService initialized with rasURI: {}", rasURI);
    }

    /**
     * We will run this nearly immediately after startup. We don't know how long it has been since the last auth micro app
     * ran its validate.
     */
    @Scheduled(initialDelay = 1000, fixedDelay = 300000)
    public void validateAllUserPassports() {
        logger.info("validateAllUserPassports() STARTING PASSPORT VALIDATION");

        Set<User> allUsersWithAPassport = this.userService.getAllUsersWithAPassport();
        if (allUsersWithAPassport.isEmpty()) {
            logger.info("validateAllUserPassports() NO USERS WITH PASSPORTS FOUND");
            return;
        }

        allUsersWithAPassport.parallelStream().forEach(user -> {
            logger.info("validateAllUserPassports() ATTEMPTING TO VALIDATE PASSPORT ___ USER {}", user.getSubject());
            if (StringUtils.isBlank(user.getPassport())) {
                logger.error("NO PASSPORT FOUND ___ USER {}", user.getSubject());
                return;
            }

            String encodedPassport = user.getPassport();
            Optional<Passport> passportOptional = JWTUtil.parsePassportJWTV11(encodedPassport);
            if (passportOptional.isEmpty()) {
                logger.error("FAILED TO DECODE PASSPORT ___ USER: {}", user.getSubject());
                user.setPassport(null);
                userService.save(user);
                cacheEvictionService.evictCache(user);
                return;
            }

            List<String> ga4ghPassportV1 = passportOptional.get().getGa4ghPassportV1();
            for (String visa : ga4ghPassportV1) {
                Optional<Ga4ghPassportV1> parsedVisa = JWTUtil.parseGa4ghPassportV1(visa);
                if (parsedVisa.isEmpty()) {
                    logger.error("validatePassport() ga4ghPassportV1 PASSPORT VISA IS EMPTY ___ USER {}", user.getSubject());
                    return;
                }

                if (parsedVisa.get().getExp() < System.currentTimeMillis() / 1000) {
                    handleFailedValidationResponse(PassportValidationResponse.VISA_EXPIRED.getValue(), user);
                } else {
                    Optional<String> response = validateVisa(visa);
                    if (response.isPresent()) {
                        boolean successfullyUpdated = handlePassportValidationResponse(response.get(), user);
                        if (!successfullyUpdated) {
                            logger.info("PASSPORT VALIDATION COMPLETE __ PASSPORT IS NO LONGER VALID ___ USER {} ___ USER LOGGED OUT", user.getSubject());
                            break;
                        } else {
                            logger.info("PASSPORT VALIDATION COMPLETE __ PASSPORT IS VALID ___ USER {}", user.getSubject());
                        }
                    }
                }
            }
        });

    }

    private boolean handlePassportValidationResponse(String response, User user) {
        PassportValidationResponse passportValidationResponse = PassportValidationResponse.fromValue(response);
        if (passportValidationResponse == null) {
            logger.error("handlePassportValidationResponse() VALIDATE PASSPORT RESPONSE WAS NULL ___ USER {}, ", user.getSubject());
            return false;
        }

        return switch (passportValidationResponse) {
            case VALID -> handleValidValidationResponse(response, user);
            case PERMISSION_UPDATE, INVALID, MISSING, INVALID_PASSPORT, VISA_EXPIRED, TXN_ERROR, EXPIRATION_ERROR, VALIDATION_ERROR,
                 EXPIRED_POLLING -> handleFailedValidationResponse(response, user);
        };
    }

    /**
     * If a passport is anything but VALID the user will be logged out and their passport be cleared.
     *
     * @param validateResponse The response from the passport validation
     * @param user             The user to log out
     * @return true if the user was successfully updated
     */
    private boolean handleFailedValidationResponse(String validateResponse, User user) {
        this.userService.save(user);
        this.userService.logoutUser(user);
        this.cacheEvictionService.evictCache(user);
        this.logger.info("handleFailedValidationResponse - {} - USER LOGGED OUT - {}", validateResponse, user.getSubject());
        return false;
    }

    private boolean handleValidValidationResponse(String validateResponse, User user) {
        this.logger.info("handleValidValidationResponse PASSPORT VALIDATE RESPONSE ___ {} ___ USER {}", validateResponse, user.getSubject());
        return true;
    }

    public Optional<String> validateVisa(String visa) {
        if (StringUtils.isBlank(visa)) {
            logger.error("validatePassport() VISA IS EMPTY");
            return Optional.empty();
        }

        MultiValueMap<String, String> queryParams = new LinkedMultiValueMap<>();
        queryParams.add("visa", visa);
        HttpEntity<MultiValueMap<String, String>> request = new HttpEntity<>(queryParams, null);

        String responseVal = PassportValidationResponse.VISA_EXPIRED.getValue();
        ResponseEntity<String> resp;
        try {
            resp = this.restClientUtil.retrievePostResponse(this.rasURI + "/passport/validate", request);
            responseVal = resp.getBody();
        } catch (Exception e) {
            logger.error("validatePassport() FAILED TO VALIDATE VISA ___ {}", e.getMessage());
        }

        return Optional.ofNullable(responseVal);
    }

    public Set<RasDbgapPermission> ga4gpPassportToRasDbgapPermissions(List<String> ga4ghPassports) {
        if (ga4ghPassports == null) {
            return null;
        }

        logger.debug("Converting ga4ghPassports to RasDbgapPermissions");
        HashSet<RasDbgapPermission> rasDbgapPermissions = new HashSet<>();
        ga4ghPassports.forEach(ga4ghPassport -> {
            Optional<Ga4ghPassportV1> parsedGa4ghPassportV1 = JWTUtil.parseGa4ghPassportV1(ga4ghPassport);
            if (parsedGa4ghPassportV1.isPresent()) {
                Ga4ghPassportV1 ga4ghPassportV1 = parsedGa4ghPassportV1.get();
                logger.info("ga4gh_passport_v1: {}", ga4ghPassportV1);

                rasDbgapPermissions.addAll(ga4ghPassportV1.getRasDbgagPermissions());
            }
        });

        return rasDbgapPermissions;
    }

    public Optional<Passport> extractPassport(JsonNode introspectResponse) {
        if (introspectResponse == null) {
            logger.error("extractPassport() introspectResponse is null");
            return Optional.empty();
        }

        if (!introspectResponse.has("passport_jwt_v11")) {
            logger.error("extractPassport() introspectResponse does not have passport_jwt_v11");
            return Optional.empty();
        }

        return JWTUtil.parsePassportJWTV11(introspectResponse.get("passport_jwt_v11").toString());
    }

    public boolean isExpired(Passport passport) {
        return passport.getExp() < System.currentTimeMillis() / 1000 &&
                passport.getIat() < System.currentTimeMillis() / 1000;
    }
}
