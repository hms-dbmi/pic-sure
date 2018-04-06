package edu.harvard.dbmi.avillach.response;

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

    public static Response success(Object content, MediaType type){
        return Response.ok(content, type)
                .build();
    }

    public static Response success(Object content, String type){
        return Response.ok(content, type)
                .build();
    }

    public static Response error(Object content) {
        return error(content, MediaType.APPLICATION_JSON_TYPE);
    }

    public static Response error(Object content, MediaType type){
        return error(DEFAULT_RESPONSE_ERROR_CODE, content, type);
    }

    public static Response error(Response.Status status, Object content){
        return error(status, content, MediaType.APPLICATION_JSON_TYPE);
    }

    public static Response error(Response.Status status, Object content, MediaType type){
        return error(status, null, content, type);
    }

    private static Response error(Response.Status status, String errorType, Object content, MediaType type){
        return Response.status(status)
                .entity(new PICSUREResponseError(errorType, content))
                .type(type)
                .build();
    }

    private static Response error(int status, String errorType, Object content, MediaType type){
        return Response.status(status)
                .entity(new PICSUREResponseError(errorType, content))
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

    /**
     *  if users are unauthorized, sending bad JSON, or anything user's fault,
     *  this method should be used
     * @param status specify what wrong behavior users sent like 400, 401, etc.
     * @param content error content
     * @return
     */
    public static Response protocolError(Response.Status status, Object content){
        return error(status, content, MediaType.APPLICATION_JSON_TYPE);
    }

    public static Response protocolError(int status, Object content){
        return error(status, null, content, MediaType.APPLICATION_JSON_TYPE);
    }

}
