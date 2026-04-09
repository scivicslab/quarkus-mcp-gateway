package com.scivicslab.mcpgateway.builtin.fs;

import com.fasterxml.jackson.databind.JsonNode;
import com.scivicslab.mcpgateway.builtin.BuiltinTool;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Optional;
import java.util.stream.Stream;

@ApplicationScoped
public class ListDirectoryTool implements BuiltinTool {

    @Inject FilesystemGuard guard;

    @Override public String name() { return "list_directory"; }
    @Override public String description() {
        return "List the contents of a directory. Returns file names, sizes, and types.";
    }
    @Override public String inputSchema() { return """
            {
              "type": "object",
              "properties": {
                "path": { "type": "string", "description": "Absolute path to the directory" }
              },
              "required": ["path"]
            }
            """; }

    @Override
    public String call(JsonNode args) {
        String pathStr = args.path("path").asText("");
        if (pathStr.isBlank()) return "Error: 'path' is required";

        Optional<String> err = guard.check(pathStr);
        if (err.isPresent()) return err.get();

        try {
            Path dir = Path.of(pathStr);
            if (!Files.isDirectory(dir)) return "Error: not a directory: " + pathStr;

            StringBuilder sb = new StringBuilder();
            try (Stream<Path> entries = Files.list(dir).sorted()) {
                entries.forEach(p -> {
                    try {
                        BasicFileAttributes attrs = Files.readAttributes(p, BasicFileAttributes.class);
                        String type = attrs.isDirectory() ? "[dir] " : "[file]";
                        String size = attrs.isDirectory() ? "" : " (" + attrs.size() + " bytes)";
                        sb.append(type).append(" ").append(p.getFileName()).append(size).append("\n");
                    } catch (IOException e) {
                        sb.append("[err]  ").append(p.getFileName()).append("\n");
                    }
                });
            }
            return sb.isEmpty() ? "(empty directory)" : sb.toString().stripTrailing();
        } catch (Exception e) {
            return "Error listing " + pathStr + ": " + e.getMessage();
        }
    }
}
