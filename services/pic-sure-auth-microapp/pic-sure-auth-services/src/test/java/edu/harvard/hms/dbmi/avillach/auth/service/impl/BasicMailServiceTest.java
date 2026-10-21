package edu.harvard.hms.dbmi.avillach.auth.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.mustachejava.Mustache;
import edu.harvard.hms.dbmi.avillach.auth.entity.User;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockitoAnnotations;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.ContextConfiguration;

import java.io.StringWriter;
import java.io.Writer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@SpringBootTest
@ContextConfiguration(classes = {BasicMailService.class})
public class BasicMailServiceTest {

    @MockBean
    private JavaMailSender mailSender;

    @MockBean
    private Mustache accessTemplate;

    @Autowired
    private BasicMailService basicMailService;

    @Autowired
    private BasicMailService injectedMailService;

    @Value("${application.template.path}")
    private String templatePath = "src/test/resources/templates/";

    @Value("${application.system.name}")
    private String systemName = "TestSystem";

    @Value("${application.access.grant.email.subject}")
    private String accessGrantEmailSubject = "Access Granted";

    @Value("${application.admin.users}")
    private String adminUsers = "admin@test.com";

    @BeforeEach
    public void setUp() {
        MockitoAnnotations.openMocks(this);
        basicMailService = new BasicMailService(mailSender, templatePath, systemName, accessGrantEmailSubject, adminUsers);
    }

    @Test
    public void testCompileTemplate_FileNotFound() {
        Mustache result = basicMailService.compileTemplate("nonExistent.mustache");
        assertNull(result);
    }

    @Test
    public void testSendUsersAccessEmail_NoTemplate() throws MessagingException {
        User user = new User();
        user.setEmail("test@test.com");
        basicMailService.setAccessTemplate(null);

        basicMailService.sendUsersAccessEmail(user);
        verify(mailSender, never()).send(any(MimeMessage.class));
    }

    @Test
    public void testSendUsersAccessEmail_NoEmail() throws MessagingException {
        User user = new User();
        user.setEmail(null);

        basicMailService.sendUsersAccessEmail(user);
        verify(mailSender, never()).send(any(MimeMessage.class));
    }

    @Test
    public void testSendDeniedAccessEmail_NoTemplate() throws MessagingException {
        JsonNode userInfo = mock(JsonNode.class);
        basicMailService.setDeniedTemplate(null);

        basicMailService.sendDeniedAccessEmail(userInfo);
        verify(mailSender, never()).send(any(MimeMessage.class));
    }

    @Test
    public void testSendUsersAccessEmail_UsesResolvedGrantSubject() throws MessagingException {
        MimeMessage message = new MimeMessage((Session) null);
        when(mailSender.createMimeMessage()).thenReturn(message);
        when(accessTemplate.execute(any(Writer.class), any(Object.class))).thenReturn(new StringWriter());
        injectedMailService.setAccessTemplate(accessTemplate);
        User user = new User();
        user.setEmail("test@test.com");

        injectedMailService.sendUsersAccessEmail(user);

        assertFalse(message.getSubject().startsWith("${"), message.getSubject());
        assertEquals(accessGrantEmailSubject, message.getSubject());
    }

    @Test
    public void testSendEmail_EmptyParams() throws MessagingException {
        basicMailService.sendEmail(accessTemplate, "", "", new Object());
        verify(mailSender, never()).send(any(MimeMessage.class));
    }

}