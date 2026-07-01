package edu.harvard.dbmi.avillach.util.response;

import javax.validation.constraints.NotEmpty;
import javax.validation.constraints.NotNull;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

/**
 * Provide ability of creating IRCT own response methods
 *
 * <p>
 * <b>Notice: </b>When implementing, several cases should be considered:
 *     <li>
 *         success response (status 200): users get what they want
 *     </li>
 *     <li>
 *         protocol error (400, 401, etc.): users' fault
 *     </li>
 *     <li>
 *          application error (500): IRCT application's fault
 *     </li>
 *     <li>
 *         RI error (500): IRCT thinks users are acting well,
 *         but resource interfaces return errors
 *     </li>
 * </p>
 *
 */
public class PICSUREResponse {

    private static final MediaType DEFAULT_MEDIA_TYPE = MediaType.APPLICATION_JSON_TYPE;
    private static final Response.Status DEFAULT_RESPONSE_ERROR_CODE = Response.Status.INTERNAL_SERVER_ERROR;

    public static Response success(){
        return Response.ok().build();
    }

    public static Response success(Object content){
        return success(content, DEFAULT_MEDIA_TYPE);
    }

    public static Response success(String message, Object content){
        return success(message, content, DEFAULT_MEDIA_TYPE);
    }
    
    public static Response success(Object content, MediaType type){
        return Response.ok(content, type)
                .build();
    }

    public static Response success(String message, Object content, MediaType type){
        return Response.ok(new PICSUREResponseOKwithMsgAndContent(message, content), type)
                .build();
    }

    public static Response error(Object content) {
        return error(content, MediaType.APPLICATION_JSON_TYPE);
    }

    public static Response error(String message, Object content) {
        return error(DEFAULT_RESPONSE_ERROR_CODE, null, message, content, DEFAULT_MEDIA_TYPE);
    }

    public static Response error(Object content, MediaType type){
        return error(DEFAULT_RESPONSE_ERROR_CODE, content, type);
    }

    public static Response error(Response.Status status, Object content){
        return error(status, content, MediaType.APPLICATION_JSON_TYPE);
    }

    public static Response error(Response.Status status, Object content, MediaType type){
    		if(status==null) status = DEFAULT_RESPONSE_ERROR_CODE;
        return error(status, null, content, type);
    }

    private static Response error(Response.Status status, String errorType, Object content, MediaType type){
        return Response.status(status.getStatusCode())
                .entity(new PICSUREResponseError(errorType, content))
                .type(type)
                .build();
    }

    private static Response error(Response.Status status, String errorType, String message, Object content, MediaType type){
        return Response.status(status.getStatusCode())
                .entity(new PICSUREResponseErrorWithMsgAndContent(errorType, message, content))
                .type(type)
                .build();
    }

    /**
     *  if IRCT application encountered some errors which is not users' fault,
     *  this method should be used
     * @param content
     * @return
     */
    public static Response applicationError(Object content){
        return error(DEFAULT_RESPONSE_ERROR_CODE, "application_error", content, DEFAULT_MEDIA_TYPE);
    }

    public static Response applicationError(String message, Object content){
        return error(DEFAULT_RESPONSE_ERROR_CODE, "application_error", message, content, DEFAULT_MEDIA_TYPE);
    }

    /**
     *  if the resource interface get some error back from the resource
     *  when the user is requesting, which means IRCT think users are doing well,
     *  and IRCT is acting well, but resources still return errors,
     *  this method should be called.
     * @param content
     * @return
     */
    public static Response riError(Object content){
        return error(DEFAULT_RESPONSE_ERROR_CODE, "ri_error", content, DEFAULT_MEDIA_TYPE);
    }

    public static Response riError(String message, Object content){
        return error(DEFAULT_RESPONSE_ERROR_CODE, "ri_error", message, content, DEFAULT_MEDIA_TYPE);
    }

    /**
     * Default method for protocol Error means client side has entered something wrong,
     * the default error status is 400
     * @param content error content
     * @return
     */
    public static Response protocolError(Object content){
        return error(Response.Status.BAD_REQUEST, content, MediaType.APPLICATION_JSON_TYPE);
    }

    public static Response protocolError(String message, Object content){
        return error(Response.Status.BAD_REQUEST, message, content, MediaType.APPLICATION_JSON_TYPE);
    }

    /**
     * @param status giving the flexibility of pointing out what specific status to return
     * @param content error content
     * @return
     */
    public static Response protocolError(Response.Status status, Object content){
        return error(status, content, MediaType.APPLICATION_JSON_TYPE);
    }

    /**
     * status code is 401
     * @param content
     * @return
     */
    public static Response unauthorizedError(Object content) {
        return error(Response.Status.UNAUTHORIZED, "Unauthorized", content, DEFAULT_MEDIA_TYPE);
    }

    public static Response unauthorizedError(String message, Object content) {
        return error(Response.Status.UNAUTHORIZED, "Unauthorized", message, content, DEFAULT_MEDIA_TYPE);
    }

}
