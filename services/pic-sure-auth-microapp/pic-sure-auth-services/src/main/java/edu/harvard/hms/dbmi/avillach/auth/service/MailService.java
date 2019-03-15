package edu.harvard.hms.dbmi.avillach.auth.service;

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
import java.io.Writer;

public class MailService {
	private static Logger logger = LoggerFactory.getLogger(MailService.class);
	private static MustacheFactory mf = new DefaultMustacheFactory();

	private static Mustache accessEmail = null;
	
	private void loadTemplates() {
		try(FileReader reader = new FileReader(JAXRSConfiguration.userActivationTemplatePath);){
			accessEmail = mf.compile(reader, "accessEmail");
		} catch (FileNotFoundException e) {
			e.printStackTrace();
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	public void sendUsersAccessEmail(User user){
		if(accessEmail==null) {
			loadTemplates();
		}
		try {
			Message message = new MimeMessage(JAXRSConfiguration.mailSession);
			String email = user.getEmail();
			if (email != null){
				InternetAddress fromEmail = new InternetAddress(JAXRSConfiguration.userActivationReplyTo);
				message.setFrom(fromEmail);
				message.setReplyTo(new Address[] {fromEmail});
				message.setRecipient(Message.RecipientType.TO, new InternetAddress(email));
				//TODO Is the subject configurable as well?  What should it say?
				message.setSubject("Your Access To " + JAXRSConfiguration.systemName);
				String body = generateBody(user);
				message.setText(body);
				Transport.send(message);
			} else {
				logger.error("User " + user.getSubject() + " has no email");
			}
		} catch (MessagingException e){
			logger.error(e.getMessage(), e);
		} catch (Exception e){
			logger.error(e.getMessage(), e);
		}
	}

	public String generateBody(User u) {
		Writer writer = accessEmail.execute(new StringWriter(),new AccessEmail(u));
		return writer.toString();
	}
}
