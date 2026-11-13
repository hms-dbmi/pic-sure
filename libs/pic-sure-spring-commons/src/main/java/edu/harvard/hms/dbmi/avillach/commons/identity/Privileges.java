package edu.harvard.hms.dbmi.avillach.commons.identity;

/**
 * Names of the privileges that services other than PSAMA check, shared so the gateway, {@code pic-sure-mcp} and {@code pic-sure-ai-service}
 * agree on them. PSAMA's own admin privileges stay in {@code AuthNaming}.
 */
public final class Privileges {

    /** Lets a user use the assistant, and so lets {@code pic-sure-ai-service} call {@code /mcp} on that user's behalf. */
    public static final String ASSISTED_SEARCH = "ASSISTED_SEARCH";

    /** Lets a caller use {@code /mcp} directly. */
    public static final String PIC_SURE_MCP = "PIC_SURE_MCP";

    private Privileges() {}
}
