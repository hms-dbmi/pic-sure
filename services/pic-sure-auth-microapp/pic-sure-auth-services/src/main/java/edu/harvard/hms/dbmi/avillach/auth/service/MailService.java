package edu.harvard.hms.dbmi.avillach.auth.service;

import com.github.mustachejava.DefaultMustacheFactory;
import com.github.mustachejava.Mustache;
import com.github.mustachejava.MustacheFactory;
import edu.harvard.hms.dbmi.avillach.auth.data.entity.User;
import org.hibernate.event.spi.PostCollectionUpdateEvent;
import org.hibernate.event.spi.PostCollectionUpdateEventListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Resource;
import javax.mail.Message;
import javax.mail.MessagingException;
import javax.mail.Session;
import javax.mail.Transport;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeMessage;
import javax.persistence.PostPersist;
import javax.ws.rs.ext.Provider;
import java.io.StringWriter;
import java.io.Writer;

//public class MailService implements Serializable, PostInsertEventListener, PostUpdateEventListener {
//public class MailService {
@Provider
public class MailService implements PostCollectionUpdateEventListener {
//public class MailService extends EmptyInterceptor{

    private Logger logger = LoggerFactory.getLogger(this.getClass());
    MustacheFactory mf = new DefaultMustacheFactory();


    //TODO Definitely not this, but the idea is that information about the system is stored somewhere
//    private String systemName = System.getProperty("systemName");
    private String systemName = "systemName";

    //TODO: Update?
    @Resource(name = "java:jboss/mail/Default")
    private Session session;


  /*  @Override
    public boolean onSave(Object entity, Serializable id,
                          Object[] state, String[] propertyNames, Type[] types){

        if (entity instanceof User){
            sendUsersAccessEmail((User)entity);
        }
        return super.onSave(entity, id, state, propertyNames, types);

    }*/

    @Override
    public void onPostUpdateCollection(PostCollectionUpdateEvent event){
        Object entity = event.getAffectedOwnerOrNull();
        if (entity != null && entity instanceof User){
            sendUsersAccessEmail((User) entity);
        }
    }

    @PostPersist
    public void onPostInsert(User u){
        sendUsersAccessEmail(u);
    }


    public void sendUsersAccessEmail(User user){
        try {
            Message message = new MimeMessage(session);
            //TODO What if there's no email
            message.setRecipient(Message.RecipientType.TO, new InternetAddress(user.getEmail()));
            //TODO Is the subject configurable as well?  What should it say?
            message.setSubject("Your Access To " + systemName);
            String body = generateBody(user);
            message.setText(body);
            logger.info(body);
            Transport.send(message);
            } catch (MessagingException e){
            //TODO: Freak out
        }
    }

    public String generateBody(User u) {
        //TODO Can we just have one factory for the whole class?
        Mustache mustache = mf.compile("email.mustache");
        Writer writer = mustache.execute(new StringWriter(),new AccessEmail(u));
        return writer.toString();
    }
}
