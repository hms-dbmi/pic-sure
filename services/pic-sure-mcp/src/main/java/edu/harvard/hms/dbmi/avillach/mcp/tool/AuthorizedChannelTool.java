package edu.harvard.hms.dbmi.avillach.mcp.tool;

/**
 * Marks a tool that reads exact data through the authorized channel. The audit aspect records {@code channel} {@code authorized} for every
 * call to such a tool, and {@code ArchitectureTest} requires every tool that uses {@code AuthorizedQueryClient} to implement it.
 */
public interface AuthorizedChannelTool {
}
