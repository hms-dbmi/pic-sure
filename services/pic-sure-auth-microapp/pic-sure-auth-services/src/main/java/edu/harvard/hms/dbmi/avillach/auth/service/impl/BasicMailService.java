package edu.harvard.hms.dbmi.avillach.auth.service.impl;

import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.StringWriter;
import java.util.Map;

import edu.harvard.hms.dbmi.avillach.auth.model.AccessEmail;
import edu.harvard.hms.dbmi.avillach.auth.service.MailService;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.mustachejava.DefaultMustacheFactory;
import com.github.mustachejava.Mustache;
import com.github.mustachejava.MustacheFactory;

import edu.harvard.hms.dbmi.avillach.auth.entity.User;

/**
 * <p>Service class for sending email notifications.</p>
 */
@Service
public class BasicMailService implements MailService {
	private static final Logger logger = LoggerFactory.getLogger(BasicMailService.class);
	private static final MustacheFactory mf = new DefaultMustacheFactory();
	private Mustache accessTemplate = compileTemplate("accessEmail.mustache");
	private Mustache deniedTemplate = compileTemplate("deniedAccessEmail.mustache");
	private final JavaMailSender mailSender;
	private final String templatePath;
	private final String systemName;
	private final String accessGrantEmailSubject;
	private final String adminUsers;


	@Autowired
	public BasicMailService(JavaMailSender mailSender,
							@Value("${application.template.path}") String templatePath,
							@Value("${application.system.name}") String systemName,
							@Value("${application.access.grant.email.subject") String accessGrantEmailSubject,
							@Value("${application.admin.users}") String adminUsers) {
        this.mailSender = mailSender;
		this.templatePath = templatePath;
        this.systemName = systemName;
        this.accessGrantEmailSubject = accessGrantEmailSubject;
        this.adminUsers = adminUsers;
    }
	
	/**
	 * Compile mustache template from templateFile
     */
    Mustache compileTemplate(String templateFile)  {
		try {
			FileReader reader = new FileReader(templatePath + templateFile);
			return mf.compile(reader, templateFile);
		} catch (FileNotFoundException e) {
			logger.warn("email template not found for " + templateFile);
			return null;
		}
	}

	/**
	 * Send email to user about changes in user Roles
	 * @param user User object
     */
	@Override
	public void sendUsersAccessEmail(User user) throws MessagingException {
		if(accessTemplate == null) {
			logger.debug("No template defined for new user access email, not sending");
		}else if (StringUtils.isEmpty(user.getEmail())) {
			logger.error("User " + (user.getSubject() != null ? user.getSubject() : "") + " has no email address.");
		} else {
			String subject = "Your Access To " + this.systemName;
			if (this.accessGrantEmailSubject != null && !this.accessGrantEmailSubject.isEmpty() && !this.accessGrantEmailSubject.equals("none")){
				subject = this.accessGrantEmailSubject;
			}
			sendEmail(accessTemplate, user.getEmail(),subject, new AccessEmail(user, this.systemName));
		}
	}

	/**
	 * Send email to admin about user being denied access to the system
	 * @param userInfo User info object returned by authentication provider
	 */
	@Override
	public void sendDeniedAccessEmail(JsonNode userInfo) throws MessagingException {
		if(deniedTemplate == null) {
			logger.debug("No template for Access Denied email, not sending");
		} else {
            userInfo.get("email");
            logger.info(userInfo.get("email").asText());
			ObjectMapper mapper = new ObjectMapper();
			Map<String, Object> scope = mapper.convertValue(userInfo, Map.class);
			scope.put("systemName", this.systemName);
			sendEmail(deniedTemplate, this.adminUsers, "User denied access to " + this.systemName, scope);
		}
	}

	/**
	 * Generate email from template and send it.
	 * @param emailTemplate Name of the template.
	 * @param to Recipients
	 * @param subject Subject of the email
	 * @param scope Object that contains attributes for template. e.g.: Map
     */
	@Override
	public void sendEmail(Mustache emailTemplate, String to, String subject, Object scope) throws MessagingException {
		logger.debug("sendEmail(String, String, String, Object) - start");
		if (StringUtils.isEmpty(to) || StringUtils.isEmpty(subject) || scope == null || emailTemplate == null) {
			logger.error("One of the required parameters is null. Can't send email.");
			return;
		}

		MimeMessage message = mailSender.createMimeMessage();
		MimeMessageHelper helper = new MimeMessageHelper(message);
		helper.setFrom(to);
		helper.setSubject(subject);
		helper.setText(emailTemplate.execute(new StringWriter(), scope).toString(), true);
		mailSender.send(message);
		logger.debug("sendEmail() finished");
	}

	public void setDeniedTemplate(Mustache deniedTemplate) {
		this.deniedTemplate = deniedTemplate;
	}

	public void setAccessTemplate(Mustache accessTemplate) {
		this.accessTemplate = accessTemplate;
	}
}
