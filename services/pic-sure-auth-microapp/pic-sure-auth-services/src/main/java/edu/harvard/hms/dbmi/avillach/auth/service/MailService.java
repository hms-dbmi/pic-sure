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

import javax.mail.Address;
import javax.mail.Message;
import javax.mail.MessagingException;
import javax.mail.Transport;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeMessage;

import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.io.StringWriter;
import java.util.Map;

public class MailService {
	private static Logger logger = LoggerFactory.getLogger(MailService.class);
	private static MustacheFactory mf = new DefaultMustacheFactory();

	private Mustache loadTemplates(String templateFile) throws IOException {
		try(FileReader reader = new FileReader(JAXRSConfiguration.emailTemplatePath + templateFile)){
			return mf.compile(reader, templateFile);
		} catch (FileNotFoundException e) {
			logger.error(e.getMessage(), e);
			throw e;
		} catch (IOException e) {
			logger.error(e.getMessage(), e);
			throw e;
		}
	}

	public void sendUsersAccessEmail(User user){
		if (user.getEmail() == null) {
			logger.error("User " + user.getSubject() + " has no email");
		} else {
			sendEmail("accessEmail.mustache", user.getEmail(),"Your Access To " + JAXRSConfiguration.systemName, new AccessEmail(user));
		}
	}

	public void sendDeniedAccessEmail(JsonNode userInfo){
		logger.info("Sending 'Access Denied' email to " + JAXRSConfiguration.adminUsers + ". User: " + userInfo.get("user_id").asText());
		ObjectMapper mapper = new ObjectMapper();
		Map<String, Object> scope = mapper.convertValue(userInfo, Map.class);
		scope.put("systemName", JAXRSConfiguration.systemName);
		if (userInfo == null) {
			logger.error("User " + scope.get("user_id") + " has no email");
			return;
		}
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
			Mustache email = loadTemplates(template);
			Message message = new MimeMessage(JAXRSConfiguration.mailSession);
			message.setRecipients(Message.RecipientType.TO, InternetAddress.parse(to));
			//TODO Is the subject configurable per email?  What should it say?
			message.setSubject(subject);
			message.setText(email.execute(new StringWriter(), scope).toString());
			Transport.send(message);
		} catch (MessagingException me) {
			logger.error("Failed to send email: '" + subject + "'", me);
		}
		catch (Exception e) {
			logger.error("Error occurred while trying to send email '" + subject + "'", e);
		}
	}
}