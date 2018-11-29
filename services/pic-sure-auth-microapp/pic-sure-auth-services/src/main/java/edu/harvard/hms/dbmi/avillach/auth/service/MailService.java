package edu.harvard.hms.dbmi.avillach.auth.service;

import com.github.mustachejava.DefaultMustacheFactory;
import com.github.mustachejava.Mustache;
import com.github.mustachejava.MustacheFactory;
import edu.harvard.hms.dbmi.avillach.auth.data.entity.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Resource;
import javax.mail.Message;
import javax.mail.MessagingException;
import javax.mail.Session;
import javax.mail.Transport;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeMessage;
import javax.naming.InitialContext;
import javax.naming.NamingException;
import java.io.StringWriter;
import java.io.Writer;

public class MailService {
    private Logger logger = LoggerFactory.getLogger(MailService.class);
    private MustacheFactory mf = new DefaultMustacheFactory();
    Mustache accessEmail = mf.compile("accessEmail.mustache");

    //TODO Where/how to store this for real?
    private String systemName = System.getenv("systemName");

    @Resource(lookup = "java:jboss/mail/gmail")
    private Session session;

    public MailService(){
        if (session ==null){
            try{
                InitialContext ic = new InitialContext();
                session = (Session) ic.lookup("java:jboss/mail/gmail");
            } catch (NamingException e){
                logger.error("No session");
            }
        }
        session.getProperties().put("mail.smtp.ssl.trust", "smtp.gmail.com");
    }

    public void sendUsersAccessEmail(User user){
        try {
            Message message = new MimeMessage(session);
            if (user.getEmail() != null){
                message.setRecipient(Message.RecipientType.TO, new InternetAddress(user.getEmail()));
                //TODO Is the subject configurable as well?  What should it say?
                message.setSubject("Your Access To " + systemName);
                String body = generateBody(user);
                message.setText(body);
                Transport.send(message);
            } else {
                logger.error("User " + user.getSubject() + " has no email");
            }
        } catch (MessagingException e){
            //TODO: Freak out
        } catch (Exception e){
            logger.error(e.getMessage());
        }
    }

    public String generateBody(User u) {
        Writer writer = accessEmail.execute(new StringWriter(),new AccessEmail(u));
        return writer.toString();
    }
}
