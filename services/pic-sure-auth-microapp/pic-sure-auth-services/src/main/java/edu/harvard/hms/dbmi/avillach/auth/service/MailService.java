package edu.harvard.hms.dbmi.avillach.auth.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.mustachejava.DefaultMustacheFactory;
import com.github.mustachejava.Mustache;
import com.github.mustachejava.MustacheFactory;
import edu.harvard.hms.dbmi.avillach.auth.JAXRSConfiguration;
import edu.harvard.hms.dbmi.avillach.auth.data.entity.User;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;

import javax.mail.Message;
import javax.mail.MessagingException;
import javax.mail.Transport;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeMessage;

import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.StringWriter;
import java.util.Map;

/**
 * <p>Service class for sending email notifications.</p>
 */
public class MailService {
	private static Logger logger = LoggerFactory.getLogger(MailService.class);
	private static MustacheFactory mf = new DefaultMustacheFactory();

	/**
	 * Compile mustache template from templateFile
	 *
	 * @throws FileNotFoundException Exception thrown if templateFile is missing due to not being configured
	 */
	private Mustache compileTemplate(String templateFile) throws FileNotFoundException {
		FileReader reader = new FileReader(JAXRSConfiguration.templatePath + templateFile);
		return mf.compile(reader, templateFile);
	}

	/**
	 * Send email to user about changes in user Roles
	 * @param user
	 */
	public void sendUsersAccessEmail(User user){
		if (StringUtils.isEmpty(user.getEmail())) {
			logger.error("User " + (user.getSubject() != null ? user.getSubject() : "") + " has no email address.");
		} else {
			String subject = "Your Access To " + JAXRSConfiguration.systemName;
			if (JAXRSConfiguration.accessGrantEmailSubject != null && !JAXRSConfiguration.accessGrantEmailSubject.isEmpty() && !JAXRSConfiguration.accessGrantEmailSubject.equals("none")){
				subject = JAXRSConfiguration.accessGrantEmailSubject;
			}
			sendEmail("accessEmail.mustache", user.getEmail(),subject, new AccessEmail(user));
		}
	}

	/**
	 * Send email to admin about user being denied access to the system
	 * @param userInfo User info object returned by authentication provider
	 */
	public void sendDeniedAccessEmail(JsonNode userInfo){
		logger.info("Sending 'Access Denied' email to "
				+ JAXRSConfiguration.adminUsers
				+ ". User: "
				+ userInfo.get("email") != null ? userInfo.get("email").asText() : userInfo.get("user_id").asText());
		ObjectMapper mapper = new ObjectMapper();
		Map<String, Object> scope = mapper.convertValue(userInfo, Map.class);
		scope.put("systemName", JAXRSConfiguration.systemName);
		sendEmail("deniedAccessEmail.mustache", JAXRSConfiguration.adminUsers, "User denied access to " + JAXRSConfiguration.systemName, scope);
	}

	/**
	 * Generate email from template and send it.
	 * @param template Name of the template.
	 * @param to Recipients
	 * @param subject Subject of the email
	 * @param scope Object that contains attributes for template. e.g.: Map
	 * @throws MessagingException
	 */
	private void sendEmail(String template, String to, String subject, Object scope) {
		logger.debug("sendEmail(String, String, String, Object) - start");
		try {
			if (StringUtils.isEmpty(template) || StringUtils.isEmpty(to) || StringUtils.isEmpty(subject) || scope == null) {
				logger.error("One of the required parameters is null. Can't send email.");
				return;
			}
			Mustache emailTemplate = compileTemplate(template);
			Message message = new MimeMessage(JAXRSConfiguration.mailSession);
			message.setRecipients(Message.RecipientType.TO, InternetAddress.parse(to));
			message.setSubject(subject);
			message.setContent(emailTemplate.execute(new StringWriter(), scope).toString(),"text/html");
			Transport.send(message);
		} catch (FileNotFoundException e) {
			logger.error("Template not found for " + template + ". Check configuration.", e);
		} catch (MessagingException me) {
			logger.error("Failed to send email: '" + subject + "'", me);
		} catch (Exception e) {
			logger.error("Error occurred while trying to send email '" + subject + "'", e);
		}
		logger.debug("sendEmail() finished");
	}
}
