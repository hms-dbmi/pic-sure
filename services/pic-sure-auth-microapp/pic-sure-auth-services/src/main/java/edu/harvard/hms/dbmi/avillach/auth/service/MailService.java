package edu.harvard.hms.dbmi.avillach.auth.service;

import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.StringWriter;
import java.util.Arrays;
import java.util.Map;
import java.util.Properties;

import javax.annotation.Resource;
import javax.inject.Inject;
import javax.mail.Message;
import javax.mail.MessagingException;
import javax.mail.Session;
import javax.mail.Transport;
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
	
//	@Inject
    private Session mailSession;
	
	Mustache accessTemplate = compileTemplate("accessEmail.mustache");
	Mustache deniedTemplate = compileTemplate("deniedAccessEmail.mustache");
	
	public MailService(){
		
		// try to define some timing parameters - wildfly doesn't read these from standalone
		
		if(mailSession == null) {
			mailSession = Session.getDefaultInstance(System.getProperties());
		}
		Properties properties = mailSession.getProperties();
		
		logger.info("Found properties in constructor " + Arrays.deepToString( properties.keySet().toArray()));
		
		//if this works, maybe we can just set system properties?
		properties.put("mail.smtp.starttls.enable","true");
		properties.put("mail.smtp.auth", "true");
		properties.put("mail.smtp.connectiontimeout", 1000);
	}
	
	/**
	 * Compile mustache template from templateFile
	 *
	 * @throws FileNotFoundException Exception thrown if templateFile is missing due to not being configured
	 */
	private Mustache compileTemplate(String templateFile)  {
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
		
//		Transport.send(message);
		
		//OK, we probably don't need to do this; Transport looks for the message.session
		
		//since wer'e using custom Session (for timeouts) we need to handle the Transport too
		Transport transport = mailSession.getTransport();
		 try {
			Properties properties = mailSession.getProperties();
			logger.info("Found properties in sendEmail " + Arrays.deepToString( properties.keySet().toArray()));
		    transport.connect(properties.getProperty("username"), properties.getProperty("password"));
			transport.sendMessage(message, message.getAllRecipients() );
	    } finally {
			transport.close();
	    }
		logger.debug("sendEmail() finished");
		
	}
}
