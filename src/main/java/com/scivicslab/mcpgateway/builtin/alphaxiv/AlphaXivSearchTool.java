package com.scivicslab.mcpgateway.builtin.alphaxiv;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.scivicslab.mcpgateway.builtin.BuiltinTool;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Built-in MCP tool: search academic papers via alphaXiv.
 *
 * <p>Supports three search modes:</p>
 * <ul>
 *   <li>{@code semantic} (default) — embedding similarity search</li>
 *   <li>{@code keyword} — full-text keyword search</li>
 *   <li>{@code agentic} — multi-step agentic paper retrieval</li>
 * </ul>
 *
 * <p>Requires alphaXiv authentication. See {@link AlphaXivClient} for setup.</p>
 */
@ApplicationScoped
public class AlphaXivSearchTool implements BuiltinTool {

    public static final String NAME = "alphaxiv_search";
    public static final String DESCRIPTION =
            "Search academic papers on alphaXiv (arXiv AI layer). "
            + "Returns papers with titles, authors, abstracts, and arXiv URLs. "
            + "Mode 'semantic' uses embedding similarity (best for conceptual queries), "
            + "'keyword' uses full-text search (best for specific terms), "
            + "'agentic' uses multi-step retrieval (best for complex research questions).";
    public static final String INPUT_SCHEMA = """
            {
              "type": "object",
              "properties": {
                "query": {
                  "type": "string",
                  "description": "Search query"
                },
                "mode": {
                  "type": "string",
                  "enum": ["semantic", "keyword", "agentic"],
                  "description": "Search mode (default: semantic)"
                }
              },
              "required": ["query"]
            }
            """;

    @Inject AlphaXivClient client;

    private final ObjectMapper mapper = new ObjectMapper();

    @Override public String name()        { return NAME; }
    @Override public String description() { return DESCRIPTION; }
    @Override public String inputSchema() { return INPUT_SCHEMA; }

    @Override
    public String call(JsonNode arguments) {
        String query = arguments.path("query").asText("").strip();
        if (query.isBlank()) return "Error: 'query' argument is required";

        String mode = arguments.path("mode").asText("semantic");

        String toolName = switch (mode) {
            case "keyword" -> "full_text_papers_search";
            case "agentic" -> "agentic_paper_retrieval";
            default        -> "embedding_similarity_search";
        };

        try {
            var args = mapper.createObjectNode();
            args.put("query", query);
            return client.callTool(toolName, args);
        } catch (Exception e) {
            return "alphaxiv_search error: " + e.getMessage();
        }
    }
}
