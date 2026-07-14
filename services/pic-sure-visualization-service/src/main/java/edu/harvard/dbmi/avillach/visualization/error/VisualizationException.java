package edu.harvard.dbmi.avillach.visualization.error;

public class VisualizationException extends RuntimeException {

    public VisualizationException(String message) {
        super(message);
    }

    public VisualizationException(String message, Throwable cause) {
        super(message, cause);
    }
}
