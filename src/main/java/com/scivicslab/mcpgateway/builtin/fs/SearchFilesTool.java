package com.scivicslab.mcpgateway.builtin.fs;

import com.fasterxml.jackson.databind.JsonNode;
import com.scivicslab.mcpgateway.builtin.BuiltinTool;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@ApplicationScoped
public class SearchFilesTool implements BuiltinTool {

    @Inject FilesystemGuard guard;

    @Override public String name() { return "search_files"; }
    @Override public String description() {
        return "Search for files matching a glob pattern under a directory. "
             + "Example pattern: **/*.pdf  or  *.txt";
    }
    @Override public String inputSchema() { return """
            {
              "type": "object",
              "properties": {
                "path": { "type": "string", "description": "Root directory to search" },
                "pattern": { "type": "string", "description": "Glob pattern (e.g. **/*.pdf)" },
                "max_results": { "type": "integer", "description": "Maximum results (default 50)" }
              },
              "required": ["path", "pattern"]
            }
            """; }

    @Override
    public String call(JsonNode args) {
        String pathStr = args.path("path").asText("");
        String pattern = args.path("pattern").asText("");
        int maxResults = args.has("max_results") ? args.get("max_results").asInt(50) : 50;

        if (pathStr.isBlank()) return "Error: 'path' is required";
        if (pattern.isBlank()) return "Error: 'pattern' is required";

        Optional<String> err = guard.check(pathStr);
        if (err.isPresent()) return err.get();

        try {
            Path root = Path.of(pathStr);
            PathMatcher matcher = FileSystems.getDefault()
                    .getPathMatcher("glob:" + pattern);

            List<String> results = new ArrayList<>();
            Files.walk(root)
                    .filter(p -> matcher.matches(p.getFileName()) || matcher.matches(root.relativize(p)))
                    .limit(maxResults)
                    .forEach(p -> results.add(p.toString()));

            if (results.isEmpty()) return "No files found matching: " + pattern;
            return String.join("\n", results);
        } catch (Exception e) {
            return "Error searching " + pathStr + ": " + e.getMessage();
        }
    }
}
