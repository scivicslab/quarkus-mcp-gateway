package com.scivicslab.mcpgateway.builtin.fs;

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Validates that requested paths are within the configured allowed directories.
 */
@ApplicationScoped
public class FilesystemGuard {

    @ConfigProperty(name = "mcp.filesystem.allowed-dirs", defaultValue = "")
    List<String> allowedDirs;

    /**
     * Resolves and validates a path. Returns the real path if allowed.
     *
     * @throws SecurityException if the path is outside all allowed directories
     */
    public Path validate(String pathStr) throws IOException {
        Path real = Path.of(pathStr).toRealPath();
        for (String dir : allowedDirs) {
            Path allowedReal = Path.of(dir).toRealPath();
            if (real.startsWith(allowedReal)) {
                return real;
            }
        }
        throw new SecurityException("Access denied: " + pathStr
                + " is outside allowed directories: " + allowedDirs);
    }

    /**
     * Like validate() but returns an error string instead of throwing.
     * Useful inside tool implementations.
     */
    public Optional<String> check(String pathStr) {
        try {
            validate(pathStr);
            return Optional.empty();
        } catch (Exception e) {
            return Optional.of("Error: " + e.getMessage());
        }
    }

    public List<String> getAllowedDirs() {
        return allowedDirs;
    }
}
