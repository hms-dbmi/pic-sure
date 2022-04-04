package edu.harvard.hms.dbmi.avillach.auth.service;

import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.StringWriter;
import java.util.Map;
import java.util.Properties;

import javax.mail.*;
import javax.mail.internet.AddressException;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeMessage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.mustachejava.DefaultMustacheFactory;
import com.github.mustachejava.Mustache;
import com.github.mustachejava.MustacheFactory;

import edu.harvard.hms.dbmi.avillach.auth.JAXRSConfiguration;
import edu.harvard.hms.dbmi.avillach.auth.data.entity.User;

/**
 * <p>Service class for sending email notifications.</p>
 */
public class MailService {
	private static Logger logger = LoggerFactory.getLogger(MailService.class);
	private static MustacheFactory mf = new DefaultMustacheFactory();
	
    private Session mailSession;
	
	private static Mustache accessTemplate = compileTemplate("accessEmail.mustache");
	private static Mustache deniedTemplate = compileTemplate("deniedAccessEmail.mustache");
	
	
	public static final int SMTP_TIMEOUT_MS = 1000;
	
	public MailService(){
		//try to read this from the app container configuration
		mailSession = JAXRSConfiguration.mailSession;
		if(mailSession == null) {
			mailSession = Session.getDefaultInstance(System.getProperties());
		}
		
		// define timeout - wildfly doesn't read this from standalone (not in xml schema)
		Properties properties = mailSession.getProperties();
		properties.put("mail.smtp.connectiontimeout", SMTP_TIMEOUT_MS);
	}
	
	/**
	 * Compile mustache template from templateFile
	 *
	 * @throws FileNotFoundException Exception thrown if templateFile is missing due to not being configured
	 */
	private static Mustache compileTemplate(String templateFile)  {
		try {
			FileReader reader = new FileReader(JAXRSConfiguration.templatePath + templateFile);
			return mf.compile(reader, templateFile);
		} catch (FileNotFoundException e) {
			logger.warn("email template not found for " + templateFile);
			return null;
		}
	}

	/**
	 * Send email to user about changes in user Roles
	 * @param user
	 * @throws MessagingException 
	 * @throws AddressException 
	 */
	public void sendUsersAccessEmail(User user) throws AddressException, MessagingException{
		if(accessTemplate == null) {
			logger.debug("No template defined for new user access email, not sending");
		}else if (StringUtils.isEmpty(user.getEmail())) {
			logger.error("User " + (user.getSubject() != null ? user.getSubject() : "") + " has no email address.");
		} else {
			String subject = "Your Access To " + JAXRSConfiguration.systemName;
			if (JAXRSConfiguration.accessGrantEmailSubject != null && !JAXRSConfiguration.accessGrantEmailSubject.isEmpty() && !JAXRSConfiguration.accessGrantEmailSubject.equals("none")){
				subject = JAXRSConfiguration.accessGrantEmailSubject;
			}
			sendEmail(accessTemplate, user.getEmail(),subject, new AccessEmail(user));
		}
	}

	/**
	 * Send email to admin about user being denied access to the system
	 * @param userInfo User info object returned by authentication provider
	 * @throws MessagingException 
	 * @throws AddressException 
	 */
	public void sendDeniedAccessEmail(JsonNode userInfo) throws AddressException, MessagingException{
		if(deniedTemplate == null) {
			logger.debug("No template for Access Denied email, not sending");
		} else {
			logger.info("Sending 'Access Denied' email to "
				+ JAXRSConfiguration.adminUsers
				+ ". User: "
				+ userInfo.get("email") != null ? userInfo.get("email").asText() : userInfo.get("user_id").asText());
			ObjectMapper mapper = new ObjectMapper();
			Map<String, Object> scope = mapper.convertValue(userInfo, Map.class);
			scope.put("systemName", JAXRSConfiguration.systemName);
			sendEmail(deniedTemplate, JAXRSConfiguration.adminUsers, "User denied access to " + JAXRSConfiguration.systemName, scope);
		}
	}

	/**
	 * Generate email from template and send it.
	 * @param template Name of the template.
	 * @param to Recipients
	 * @param subject Subject of the email
	 * @param scope Object that contains attributes for template. e.g.: Map
	 * @throws AddressException 
	 * @throws MessagingException
	 */
	private void sendEmail(Mustache emailTemplate, String to, String subject, Object scope) throws AddressException, MessagingException {
		logger.debug("sendEmail(String, String, String, Object) - start");
		if (StringUtils.isEmpty(to) || StringUtils.isEmpty(subject) || scope == null || emailTemplate == null) {
			logger.error("One of the required parameters is null. Can't send email.");
			return;
		}
		
		Message message = new MimeMessage(mailSession);
		message.setRecipients(Message.RecipientType.TO, InternetAddress.parse(to));
		message.setSubject(subject);
		message.setContent(emailTemplate.execute(new StringWriter(), scope).toString(),"text/html");
		
		Transport.send(message);
		logger.debug("sendEmail() finished");
		
	}
}
