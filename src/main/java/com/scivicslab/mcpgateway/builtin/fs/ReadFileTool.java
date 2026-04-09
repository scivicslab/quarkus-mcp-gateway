package com.scivicslab.mcpgateway.builtin.fs;

import com.fasterxml.jackson.databind.JsonNode;
import com.scivicslab.mcpgateway.builtin.BuiltinTool;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

@ApplicationScoped
public class ReadFileTool implements BuiltinTool {

    private static final int DEFAULT_MAX_LENGTH = 20000;

    @Inject FilesystemGuard guard;

    @Override public String name() { return "read_file"; }
    @Override public String description() {
        return "Read the text content of a file. Returns the file contents as a string.";
    }
    @Override public String inputSchema() { return """
            {
              "type": "object",
              "properties": {
                "path": { "type": "string", "description": "Absolute path to the file" },
                "max_length": { "type": "integer", "description": "Maximum characters to return (default 20000)" }
              },
              "required": ["path"]
            }
            """; }

    @Override
    public String call(JsonNode args) {
        String pathStr = args.path("path").asText("");
        if (pathStr.isBlank()) return "Error: 'path' is required";

        int maxLength = args.has("max_length")
                ? args.get("max_length").asInt(DEFAULT_MAX_LENGTH) : DEFAULT_MAX_LENGTH;

        Optional<String> err = guard.check(pathStr);
        if (err.isPresent()) return err.get();

        try {
            Path path = Path.of(pathStr);
            if (!Files.isRegularFile(path)) return "Error: not a regular file: " + pathStr;

            String content = Files.readString(path);
            if (content.length() <= maxLength) return content;
            return content.substring(0, maxLength)
                    + "\n[truncated — " + content.length() + " chars total]";
        } catch (Exception e) {
            return "Error reading " + pathStr + ": " + e.getMessage();
        }
    }
}
