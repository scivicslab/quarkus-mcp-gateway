package com.scivicslab.mcpgateway.registry;

import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Reads servers.yaml on startup and auto-registers MCP servers.
 *
 * Looks for:
 *   1. ./servers.yaml  (current directory — for easy customization)
 *   2. classpath:servers.yaml  (bundled default)
 */
@ApplicationScoped
public class AutoRegistrar {

    private static final Logger logger = Logger.getLogger(AutoRegistrar.class.getName());

    @Inject
    ServerRegistry registry;

    void onStart(@Observes StartupEvent event) {
        Map<String, Object> config = loadConfig();
        if (config == null) {
            logger.info("No servers.yaml found — starting with empty registry");
            return;
        }

        Object serversObj = config.get("servers");
        if (!(serversObj instanceof List<?> servers)) {
            logger.warning("servers.yaml: 'servers' must be a list");
            return;
        }

        for (Object entry : servers) {
            if (entry instanceof Map<?, ?> map) {
                String name = str(map.get("name"));
                String url = str(map.get("url"));
                String description = str(map.get("description"));

                if (name == null || url == null) {
                    logger.warning("servers.yaml: entry missing 'name' or 'url', skipping: " + map);
                    continue;
                }

                registry.register(name, url, description != null ? description : "");
                logger.info("Auto-registered: " + name + " -> " + url);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> loadConfig() {
        Yaml yaml = new Yaml();

        // 1. Try external file in current directory
        Path external = Path.of("servers.yaml");
        if (Files.exists(external)) {
            try (InputStream is = Files.newInputStream(external)) {
                logger.info("Loading servers from: " + external.toAbsolutePath());
                return yaml.load(is);
            } catch (Exception e) {
                logger.log(Level.WARNING, "Failed to read external servers.yaml", e);
            }
        }

        // 2. Fall back to classpath resource
        try (InputStream is = Thread.currentThread().getContextClassLoader()
                .getResourceAsStream("servers.yaml")) {
            if (is != null) {
                logger.info("Loading servers from classpath:servers.yaml");
                return yaml.load(is);
            }
        } catch (Exception e) {
            logger.log(Level.WARNING, "Failed to read classpath servers.yaml", e);
        }

        return null;
    }

    private static String str(Object obj) {
        return obj != null ? obj.toString() : null;
    }
}
