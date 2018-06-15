package edu.harvard.dbmi.avillach.util.response;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;

@JsonSerialize
public class PICSUREResponseOKwithMsgAndContent {

    private String message;

    private Object content;

    public PICSUREResponseOKwithMsgAndContent(String message, Object content) {
        this.message = message;
        this.content = content;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public Object getContent() {
        return content;
    }

    public void setContent(Object content) {
        this.content = content;
    }
}
