package edu.harvard.dbmi.avillach.exception;

public class ResourceCommunicationException extends RuntimeException {

	private static final long serialVersionUID = -3039213913753996987L;

	public ResourceCommunicationException(String targetIrctUrl, Exception e) {
		super("An error has occurred attempting to process a request for " + targetIrctUrl, e);
	}

	public ResourceCommunicationException(String targetIrctUrl, String message){
		super("An error has occurred attempting to process a request for " + targetIrctUrl + ": " + message);
	}
	
}
