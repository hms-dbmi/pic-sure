package edu.harvard.dbmi.avillach.response;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;

import java.io.Serializable;

@JsonSerialize
public class PICSUREResponseError implements Serializable {

    private String errorType = "error";

    private Object message;

    public PICSUREResponseError() {
    }

    public PICSUREResponseError(String errorType) {
        this.errorType = errorType;
    }

    public PICSUREResponseError(Object message) {
        this.message = message;
    }

    public PICSUREResponseError(String errorType, Object message) {
        if (errorType != null && !errorType.isEmpty())
            this.errorType = errorType;
        this.message = message;
    }

    public String getErrorType() {
        return errorType;
    }

    public void setErrorType(String errorType) {
        this.errorType = errorType;
    }

    public Object getMessage() {
        return message;
    }

    public void setMessage(Object message) {
        this.message = message;
    }
}
