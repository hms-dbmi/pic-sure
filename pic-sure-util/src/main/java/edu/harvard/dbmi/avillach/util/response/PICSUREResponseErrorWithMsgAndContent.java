package edu.harvard.dbmi.avillach.util.response;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;

@JsonSerialize
public class PICSUREResponseErrorWithMsgAndContent {

    private String errorType = "error";

    private String message;

    private Object content;

    public PICSUREResponseErrorWithMsgAndContent(String errorType, String message, Object content) {
        if (errorType != null && !errorType.isEmpty())
            this.errorType = errorType;
        this.message = message;
        this.content = content;
    }

    public String getErrorType() {
        return errorType;
    }

    public void setErrorType(String errorType) {
        this.errorType = errorType;
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
