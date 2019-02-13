package edu.harvard.hms.dbmi.avillach.auth.service.auth;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.UUID;

public class AuthorizationService {
	private Logger logger = LoggerFactory.getLogger(AuthorizationService.class);

	@Inject
	UserRepository userRepo;

	/**
	 * Checking based on AccessRule in Privilege
	 * @param requestBody
	 * @param object 
	 * @param securityContext
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
