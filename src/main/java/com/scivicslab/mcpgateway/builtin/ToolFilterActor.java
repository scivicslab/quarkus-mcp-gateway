package com.scivicslab.mcpgateway.builtin;

import java.util.List;
import java.util.Set;

/**
 * Extension point for tool-specific filtering logic in find_tools search results.
 *
 * <p>Implement this interface and annotate with {@code @ApplicationScoped} to register
 * a filter for specific tool names. FindToolsTool dispatches matching entries to each
 * registered actor after the AllToolsCache Lucene search completes.</p>
 *
 * <p>Tool names across all registered actors must be unique. Duplicate registrations
 * cause an exception at startup.</p>
 */
public interface ToolFilterActor {

    /** Tool names this actor handles. Must be disjoint across all registered actors. */
    Set<String> handledToolNames();

    /**
     * Filter the candidate entries for this actor's tools.
     *
     * @param query      the natural language query passed to find_tools
     * @param candidates entries whose names are in {@link #handledToolNames()}
     * @return included entries to return to the LLM, or a guidance message if entries is empty
     */
    ToolFilterResult filter(String query, List<AllToolsCache.Entry> candidates);
}
