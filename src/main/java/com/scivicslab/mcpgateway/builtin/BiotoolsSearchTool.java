package com.scivicslab.mcpgateway.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.enterprise.context.ApplicationScoped;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.queryparser.classic.MultiFieldQueryParser;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.store.FSDirectory;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.logging.Logger;

/**
 * Built-in MCP tool: search the biotools Singularity container index.
 *
 * <p>Returns container paths matching the given tool name query.
 * The Lucene index must be pre-built by BiotoolsIndexer.</p>
 */
@ApplicationScoped
public class BiotoolsSearchTool implements BuiltinTool {

    private static final Logger logger = Logger.getLogger(BiotoolsSearchTool.class.getName());
    private static final int DEFAULT_MAX_RESULTS = 20;

    @ConfigProperty(name = "mcp.biotools.index-dir",
                    defaultValue = "/mnt/stonefly520/MCP_data/biotools-index")
    String indexDir;

    @Override public String name() { return "find_biotool"; }
    @Override public String description() {
        return "Search the biotools Singularity container index by tool name. "
             + "Returns matching container paths with version info. "
             + "Supports partial name matching and wildcards (e.g. 'bwa*', 'samtools').";
    }
    @Override public String inputSchema() { return """
            {
              "type": "object",
              "properties": {
                "query": {
                  "type": "string",
                  "description": "Tool name to search for (partial match supported)"
                },
                "max_results": {
                  "type": "integer",
                  "description": "Maximum results to return (default 20)"
                }
              },
              "required": ["query"]
            }
            """; }

    @Override
    public String call(JsonNode arguments) {
        String query = arguments.path("query").asText("").strip();
        if (query.isBlank()) return "Error: 'query' is required";

        int maxResults = arguments.has("max_results")
                ? arguments.get("max_results").asInt(DEFAULT_MAX_RESULTS) : DEFAULT_MAX_RESULTS;

        Path idx = Path.of(indexDir);
        if (!Files.exists(idx)) {
            return "Index not found at " + indexDir + ". Run BiotoolsIndexer first.";
        }

        try (FSDirectory dir = FSDirectory.open(idx);
             DirectoryReader reader = DirectoryReader.open(dir)) {

            IndexSearcher searcher = new IndexSearcher(reader);

            // Append wildcard if no special chars — makes partial name matching intuitive
            String luceneQuery = query.contains("*") || query.contains("?") || query.contains("~")
                    ? query
                    : query + "*";

            MultiFieldQueryParser parser = new MultiFieldQueryParser(
                    new String[]{"name", "filename"}, new StandardAnalyzer());
            parser.setAllowLeadingWildcard(true);
            Query q = parser.parse(luceneQuery);

            ScoreDoc[] hits = searcher.search(q, maxResults).scoreDocs;
            if (hits.length == 0) return "No containers found matching: " + query;

            StringBuilder sb = new StringBuilder();
            sb.append("Found ").append(hits.length).append(" result(s) for \"").append(query).append("\":\n\n");
            for (ScoreDoc hit : hits) {
                Document doc = searcher.storedFields().document(hit.doc);
                sb.append("name:    ").append(doc.get("name")).append("\n");
                sb.append("version: ").append(doc.get("version")).append("\n");
                sb.append("path:    ").append(doc.get("path")).append("\n\n");
            }
            return sb.toString().stripTrailing();

        } catch (Exception e) {
            logger.warning("Biotools search failed: " + e.getMessage());
            return "Search error: " + e.getMessage();
        }
    }
}
