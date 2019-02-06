package edu.harvard.hms.dbmi.avillach.auth.service.auth;

import com.jayway.jsonpath.JsonPath;
import edu.harvard.hms.dbmi.avillach.auth.data.entity.AccessRule;
import edu.harvard.hms.dbmi.avillach.auth.data.entity.User;
import edu.harvard.hms.dbmi.avillach.auth.data.repository.UserRepository;
import net.minidev.json.JSONArray;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.ws.rs.core.SecurityContext;
import java.util.Set;

public class AuthorizationService {
    private Logger logger = LoggerFactory.getLogger(AuthorizationService.class);

    @Inject
    UserRepository userRepo;

    /**
     * Checking based on AccessRule in Privilege
     * @param requestBody
     * @param securityContext
     * @return
     *
     * @see edu.harvard.hms.dbmi.avillach.auth.data.entity.Privilege
     * @see AccessRule
     */
    public boolean isAuthorized(Object requestBody, SecurityContext securityContext){

        //in some cases, we don't do checking
        if (requestBody == null)
            return true;

//        Object parsedRequestBody = null;
//        Configuration conf = Configuration.defaultConfiguration().addOptions(Option.DEFAULT_PATH_LEAF_TO_NULL).addOptions(Option.ALWAYS_RETURN_LIST);
//        try {
//            parsedRequestBody = conf.jsonProvider().parse(JAXRSConfiguration.objectMapper.writeValueAsString(requestBody));
//        } catch (JsonProcessingException e) {
//            return true;
//        }

        // start to process the jsonpath checking
        boolean result = true;

        User user = userRepo.getUniqueResultByColumn("subject",securityContext.getUserPrincipal().getName());

        Set<AccessRule> accessRules = user.getTotalAccessRule();
        if (accessRules == null || accessRules.isEmpty())
            return true;

        for (AccessRule accessRule : accessRules) {

            if (!checkAccessRule(requestBody, accessRule)){
                result = false;
                break;
            }
        }

        return result;
    }

    private boolean checkAccessRule(Object parsedRequestBody, AccessRule accessRule){
        String rule = accessRule.getRule();
        String value = accessRule.getValue();

        if (rule == null || rule.isEmpty())
            return true;

        Object requestBodyValue = JsonPath.parse(parsedRequestBody).read(rule);

        if (value == null){
            if (requestBodyValue == null)
                return true;
            else
                return false;
        }

        /**
         * NOTE: if the path(driven by attribute rule) eventually leads to String values, we can do check,
         * otherwise, only means the path is not driving to useful places, just return true.
         */
        if (requestBodyValue instanceof String){
            return decisionMaker(accessRule, (String)requestBodyValue, value);
        } else if (requestBodyValue instanceof JSONArray) {
            for (Object item : (JSONArray)requestBodyValue){
                if (item instanceof String) {
                    if (decisionMaker(accessRule, (String)item, value) == false){
                        return false;
                    }
                }
            }
        }

        return true;

    }

    private boolean decisionMaker(AccessRule accessRule, String requestBodyValue, String value){
        switch (accessRule.getType()){
            case AccessRule.TypeNaming.NOT_CONTAINS:
                if (!value.contains(requestBodyValue))
                    return true;
                else
                    return false;
            case AccessRule.TypeNaming.NOT_CONTAINS_IGNORE_CASE:
                if (!value.toLowerCase().equals(requestBodyValue.toLowerCase()))
                    return true;
                else
                    return false;
            case(AccessRule.TypeNaming.NOT_EQUALS):
                if (!value.equals(requestBodyValue))
                    return true;
                else
                    return false;
            default:
                logger.warn("checkAccessRule() incoming accessRule type is out of scope. Just return true.");
                return true;
        }
    }

}
