package edu.harvard.dbmi.avillach.exception;

import javax.ws.rs.WebApplicationException;

/**
 * Will end up to return a RI error
 *
 * @see edu.harvard.hms.dbmi.bd2k.irct.util.IRCTResponse
 */
public class ResourceInterfaceException extends WebApplicationException {
	private static final long serialVersionUID = -4688536480746747740L;

	private Object content;

	public ResourceInterfaceException() {
	}

	public ResourceInterfaceException(Object content) {
		this.content = content;
	}

	/**
	 * Create a Resource Interface Exception with the given message
	 * 
	 * @param message Messsage
	 */
	public ResourceInterfaceException(String message) {
		super(message);
	}

	/**
	 * Create a Resource Interface Exception
	 * 
	 * @param exception Exception
	 */
	public ResourceInterfaceException(Exception exception) {
		super(exception);
	}
	
	/**
	 * Create a Resource Interface Exception with the given message
	 * 
	 * @param message Message
	 * @param exception Exception
	 */
	public ResourceInterfaceException(String message, Exception exception) {
		super(message, exception);
	}

	public Object getContent() {
		return content;
	}

	public void setContent(Object content) {
		this.content = content;
	}
}
