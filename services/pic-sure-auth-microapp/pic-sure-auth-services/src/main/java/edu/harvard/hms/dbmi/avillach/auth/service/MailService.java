package edu.harvard.hms.dbmi.avillach.auth.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.mustachejava.Mustache;
import edu.harvard.hms.dbmi.avillach.auth.entity.User;
import jakarta.mail.MessagingException;

public interface MailService {
    void sendUsersAccessEmail(User user) throws MessagingException;

    void sendDeniedAccessEmail(JsonNode userInfo) throws MessagingException;

    void sendEmail(Mustache emailTemplate, String to, String subject, Object scope) throws MessagingException;
}
