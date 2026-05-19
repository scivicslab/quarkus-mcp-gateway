package com.scivicslab.mcpgateway.builtin;

import java.util.List;

/**
 * Result returned by {@link ToolFilterActor#filter}.
 *
 * <p>When {@code guidance} is non-null, FindToolsTool returns the guidance string to the
 * LLM instead of a tool list. When {@code guidance} is null, {@code included} entries are
 * merged with other results and returned as a JSON array.</p>
 */
public record ToolFilterResult(
        List<AllToolsCache.Entry> included,
        String guidance) {

    public static ToolFilterResult of(List<AllToolsCache.Entry> included) {
        return new ToolFilterResult(included, null);
    }

    public static ToolFilterResult guidance(String message) {
        return new ToolFilterResult(List.of(), message);
    }
}
