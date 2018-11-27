package edu.harvard.hms.dbmi.avillach.auth.service;

import com.github.mustachejava.DefaultMustacheFactory;
import com.github.mustachejava.Mustache;
import com.github.mustachejava.MustacheFactory;
import edu.harvard.hms.dbmi.avillach.auth.data.entity.User;
import org.hibernate.envers.boot.internal.EnversService;
import org.hibernate.envers.event.spi.BaseEnversCollectionEventListener;
import org.hibernate.event.spi.PostInsertEvent;
import org.hibernate.event.spi.PostInsertEventListener;
import org.hibernate.event.spi.PreCollectionUpdateEvent;
import org.hibernate.event.spi.PreCollectionUpdateEventListener;
import org.hibernate.persister.entity.EntityPersister;
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

public class MailService extends BaseEnversCollectionEventListener implements PostInsertEventListener, PreCollectionUpdateEventListener {
    private Logger logger = LoggerFactory.getLogger(this.getClass());
    MustacheFactory mf = new DefaultMustacheFactory();


    //TODO Definitely not this, but the idea is that information about the system is stored somewhere
//    private String systemName = System.getProperty("systemName");
    private String systemName = "systemName";


    //TODO: Update? Get this to work actually
    @Resource(mappedName = "java:jboss/mail/gmail")
    private Session session;

    public MailService(EnversService es){
        super(es);
        if (session ==null){
            try{
                InitialContext ic = new InitialContext();
                session = (Session) ic.lookup("java:jboss/mail/gmail");
            } catch (NamingException e){
                logger.info("No session");
            }
        }

    }

    public void onPreUpdateCollection(PreCollectionUpdateEvent e){
        Object entity = e.getAffectedOwnerOrNull();
        if (entity instanceof User){
            sendUsersAccessEmail((User)entity);
        }
    }

    public void onPostInsert(PostInsertEvent e){
        Object entity = e.getEntity();
        if (entity instanceof User){
            sendUsersAccessEmail((User)entity);
        }
    }

    public boolean requiresPostCommitHanding(EntityPersister persister) {
        return this.getEnversService().getEntitiesConfigurations().isVersioned(persister.getEntityName());
    }

    public void sendUsersAccessEmail(User user){
        try {
            Message message = new MimeMessage(session);
//            session.getProperties();
            //TODO What if there's no email
            if (user.getEmail() != null){
                message.setRecipient(Message.RecipientType.TO, new InternetAddress(user.getEmail()));
                //TODO Is the subject configurable as well?  What should it say?
                message.setSubject("Your Access To " + systemName);
                String body = generateBody(user);
                message.setText(body);
                logger.info(body);
                Transport.send(message);
            }
        } catch (MessagingException e){
            //TODO: Freak out
        } catch (Exception e){
            logger.error(e.getMessage());
        }
    }

    public String generateBody(User u) {
        //TODO Can we just have one factory for the whole class?
        Mustache mustache = mf.compile("email.mustache");
        Writer writer = mustache.execute(new StringWriter(),new AccessEmail(u));
        return writer.toString();
    }
}
