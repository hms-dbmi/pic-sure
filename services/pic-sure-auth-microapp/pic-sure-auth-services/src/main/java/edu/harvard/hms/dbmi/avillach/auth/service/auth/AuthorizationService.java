package edu.harvard.hms.dbmi.avillach.auth.service.auth;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.PathNotFoundException;
import edu.harvard.hms.dbmi.avillach.auth.data.entity.AccessRule;
import edu.harvard.hms.dbmi.avillach.auth.data.entity.User;
import edu.harvard.hms.dbmi.avillach.auth.data.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import java.util.*;

public class AuthorizationService {
	private Logger logger = LoggerFactory.getLogger(AuthorizationService.class);

	@Inject
	UserRepository userRepo;

	/**
	 * Checking based on AccessRule in Privilege
	 *
	 * @param applicationName
	 * @param requestBody
	 * @param userUuid
	 * @return
	 *
	 * @see edu.harvard.hms.dbmi.avillach.auth.data.entity.Privilege
	 * @see AccessRule
	 */
	public boolean isAuthorized(String applicationName , Object requestBody, UUID userUuid){

		User user = userRepo.getById(userUuid);
		
		//in some cases, we don't do checking
		if (requestBody == null) {
			logger.info("ACCESS_LOG ___ " + user.getUuid().toString() + "," + user.getEmail() + "," + user.getName() + 
					" ___ has been granted access to application ___ " + applicationName + " ___ NO REQUEST BODY FORWARDED BY APPLICATION");        
			return true;
		}

		//        Object parsedRequestBody = null;
		//        Configuration conf = Configuration.defaultConfiguration().addOptions(Option.DEFAULT_PATH_LEAF_TO_NULL).addOptions(Option.ALWAYS_RETURN_LIST);
		//        try {
		//            parsedRequestBody = conf.jsonProvider().parse(JAXRSConfiguration.objectMapper.writeValueAsString(requestBody));
		//        } catch (JsonProcessingException e) {
		//            return true;
		//        }

		// start to process the jsonpath checking
		boolean result = true;

		String requestJson = null;
		try {
			requestJson = new ObjectMapper().writeValueAsString(requestBody);
		} catch (JsonProcessingException e1) {
			// TODO Auto-generated catch block
			e1.printStackTrace();
			logger.info("ACCESS_LOG ___ " + user.getUuid().toString() + "," + user.getEmail() + "," + user.getName() + 
					" ___ has been denied access to execute query ___ " + requestBody + " ___ in application ___ " + applicationName + " ___ UNABLE TO PARSE REQUEST");
			return false;
		}

		Set<AccessRule> accessRules = user.getTotalAccessRule();
		if (accessRules == null || accessRules.isEmpty()) {
			logger.info("ACCESS_LOG ___ " + user.getUuid().toString() + "," + user.getEmail() + "," + user.getName() + 
					" ___ has been granted access to execute query ___ " + requestJson + " ___ in application ___ " + applicationName + " ___ NO ACCESS RULES EVALUATED");
			return true;        	
		}

		AccessRule failedRule = null;
		for (AccessRule accessRule : accessRules) {

			if (!checkAccessRule(requestBody, accessRule)){
				result = false;
				failedRule = accessRule;
				break;
			}
		}

		logger.info("ACCESS_LOG ___ " + user.getUuid().toString() + "," + user.getEmail() + "," + user.getName() + 
				" ___ has been " + (result?"granted":"denied") + " access to execute query ___ " + requestJson + 
				" ___ in application ___ " + applicationName + " ___ " + (result?"":failedRule.getName()));

		return result;
	}

	protected boolean checkAccessRule(Object parsedRequestBody, AccessRule accessRule) {

		Set<AccessRule> gates = accessRule.getGates();

		boolean applyGate = false;

		// check every each gate first to see if gate will be apply
		if (gates != null && !gates.isEmpty()) {
            applyGate = true;
			for (AccessRule gate : gates){
			    if (!extractAndCheckRule(gate, parsedRequestBody)){
			        applyGate = false;
			        break;
                }
			}
		}

        if (gates == null || gates.isEmpty() || applyGate) {
            if (extractAndCheckRule(accessRule, parsedRequestBody) == false)
                return false;
            else {
                if (accessRule.getSubAccessRule() != null) {
                    for (AccessRule subAccessRule : accessRule.getSubAccessRule()) {
                        if (extractAndCheckRule(subAccessRule, parsedRequestBody) == false)
                            return false;
                    }
                }
            }
        }

        return true;
	}

	private boolean extractAndCheckRule(AccessRule accessRule, Object parsedRequestBody){
        String rule = accessRule.getRule();
        String value = accessRule.getValue();

        if (rule == null || rule.isEmpty())
            return true;

        Object requestBodyValue;

        try {
            requestBodyValue = JsonPath.parse(parsedRequestBody).read(rule);
        } catch (PathNotFoundException ex){
            logger.error("extractAndCheckRule() -> JsonPath.parse().read() throws exception: " + ex.getClass().getSimpleName() + " - " + ex.getMessage());
            return false;
        }

        // AccessRule type IS_EMPTY is very special, needs to be checked in front of any others
        if (accessRule.getType() == AccessRule.TypeNaming.IS_EMPTY){
            if (requestBodyValue == null
                    || (requestBodyValue instanceof String && ((String)requestBodyValue).isEmpty())
                    || (requestBodyValue instanceof Collection && ((Collection)requestBodyValue).isEmpty())
                    || (requestBodyValue instanceof Map && ((Map)requestBodyValue).isEmpty()))
                return true;
            else
                return false;
        }

        if (value == null){
            if (requestBodyValue == null)
                return true;
            else
                return false;
        }

        return evaluateNode(requestBodyValue, accessRule, value);
    }


    private boolean evaluateNode(Object requestBodyValue, AccessRule accessRule, String value){
        /**
         * NOTE: if the path(driven by attribute rule) eventually leads to String values, we can do check,
         * otherwise, only means the path is not driving to useful places, just return true.
         *
         * a possible requestBodyValue could be:
         *
         *     NOTE: this size 13 JSONArray contains every element in the root node,
         *     which means the parent node and child node will be separately counted, meaning
         *     13 elements contains duplicated but parent-child related nodes.
         *
         * requestBodyValue = {JSONArray@1560}  size = 13
         *  0 = {LinkedHashMap@1566}  size = 5
         *  1 = "8e8c7ed0-87ea-4342-b8da-f939e46bac26"
         *  2 = {LinkedHashMap@1568}  size = 0
         *  3 = {LinkedHashMap@1569}  size = 1
         *  4 = {LinkedHashMap@1570}  size = 0
         *  5 = {ArrayList@1571}  size = 1
         *  6 = "COUNT"
         *  7 = {ArrayList@1573}  size = 2
         *  8 = {ArrayList@1574}  size = 1
         *  9 = "male"
         *  10 = "\demographics\AGE\"
         *  11 = "\demographics\SEX\"
         *  12 = "\demographics\AGE\"
         */

        if (requestBodyValue instanceof String){
            return decisionMaker(accessRule, (String)requestBodyValue, value);
        } else if (requestBodyValue instanceof Collection) {
            switch (accessRule.getType()){
                case (AccessRule.TypeNaming.ARRAY_EQUALS):
                case (AccessRule.TypeNaming.ARRAY_CONTAINS):
                case(AccessRule.TypeNaming.ARRAY_REG_MATCH):
                    for (Object item : (Collection)requestBodyValue) {
                        if (item instanceof String){
                            if (decisionMaker(accessRule, (String)item, value)){
                                return true;
                            }
                        } else {
                            if (evaluateNode(item, accessRule, value)){
                                return true;
                            }
                        }
                    }
                    return false;
                default:
                    for (Object item : (Collection)requestBodyValue){
                        if (item instanceof String) {
                            if (decisionMaker(accessRule, (String)item, value) == false){
                                return false;
                            }
                        } else {
                            if (evaluateNode(item, accessRule, value) == false)
                                return false;
                        }
                    }
            }
        } else if (accessRule.isCheckMapNode() && requestBodyValue instanceof Map) {
            switch (accessRule.getType()) {
                case (AccessRule.TypeNaming.ARRAY_EQUALS):
                case (AccessRule.TypeNaming.ARRAY_CONTAINS):
                case(AccessRule.TypeNaming.ARRAY_REG_MATCH):
                    for (Map.Entry entry : ((Map<String, Object>) requestBodyValue).entrySet()){
                        if (decisionMaker(accessRule, (String) entry.getKey(), value))
                            return true;

                        if(!accessRule.isCheckMapKeyOnly() && evaluateNode(entry.getValue(), accessRule, value))
                            return true;
                    }
                    return false;
                default:
                    for (Map.Entry entry : ((Map<String, Object>) requestBodyValue).entrySet()){
                        if (decisionMaker(accessRule, (String) entry.getKey(), value) == false)
                            return false;

                        if(!accessRule.isCheckMapKeyOnly() && evaluateNode(entry.getValue(), accessRule, value) == false)
                            return false;
                    }

            }

        }

        return true;
    }

	private boolean decisionMaker(AccessRule accessRule, String requestBodyValue, String value){
		switch (accessRule.getType()){
            case AccessRule.TypeNaming.NOT_CONTAINS:
                if (!requestBodyValue.contains(value))
                    return true;
                else
                    return false;
            case AccessRule.TypeNaming.NOT_CONTAINS_IGNORE_CASE:
                if (!requestBodyValue.toLowerCase().contains(value.toLowerCase()))
                    return true;
                else
                    return false;
            case(AccessRule.TypeNaming.NOT_EQUALS):
                if (!value.equals(requestBodyValue))
                    return true;
                else
                    return false;
            case(AccessRule.TypeNaming.ARRAY_EQUALS):
            case(AccessRule.TypeNaming.EQUALS):
                if (value.equals(requestBodyValue))
                    return true;
                else
                    return false;
            case(AccessRule.TypeNaming.ALL_CONTAINS):
            case(AccessRule.TypeNaming.ARRAY_CONTAINS):
                if (requestBodyValue.contains(value))
                    return true;
                else
                    return false;
            case(AccessRule.TypeNaming.CONTAINS_IGNORE_CASE):
                if (requestBodyValue.toLowerCase().contains(value.toLowerCase()))
                    return true;
                else
                    return false;
            case(AccessRule.TypeNaming.NOT_EQUALS_IGNORE_CASE):
                if (!value.equalsIgnoreCase(requestBodyValue))
                    return true;
                else
                    return false;
            case(AccessRule.TypeNaming.EQUALS_IGNORE_CASE):
                if (value.equalsIgnoreCase(requestBodyValue))
                    return true;
                else
                    return false;
            case(AccessRule.TypeNaming.ALL_REG_MATCH):
            case(AccessRule.TypeNaming.ARRAY_REG_MATCH):
                if (requestBodyValue.matches(value))
                    return true;
                else
                    return false;
            case(AccessRule.TypeNaming.IS_EMPTY):
//                if (requestBodyValue == null
//                        || (requestBodyValue instanceof String && requestBodyValue.isEmpty())
//                        || (requestBodyValue instanceof Collection))
//                    return true;


		default:
			logger.warn("checkAccessRule() incoming accessRule type is out of scope. Just return true.");
			return true;
		}
	}

}
