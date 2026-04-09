package com.scivicslab.mcpgateway.stdio;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Reads the "stdio" section from servers.yaml on startup and launches
 * each entry as a subprocess, performing the MCP initialize handshake.
 *
 * servers.yaml example:
 * <pre>
 * stdio:
 *   - name: filesystem
 *     command: npx @modelcontextprotocol/server-filesystem /home/devteam
 *   - name: fetch
 *     command: npx @modelcontextprotocol/server-fetch
 * </pre>
 */
@ApplicationScoped
public class StdioLauncher {

    private static final Logger logger = Logger.getLogger(StdioLauncher.class.getName());
    private static final ObjectMapper mapper = new ObjectMapper();

    @Inject
    StdioRegistry registry;

    void onStart(@Observes StartupEvent ev) {
        Map<String, Object> config = loadConfig();
        if (config == null) return;

        Object stdioObj = config.get("stdio");
        if (!(stdioObj instanceof List<?> stdioList)) return;

        for (Object entry : stdioList) {
            if (!(entry instanceof Map<?, ?> map)) continue;
            String name = str(map.get("name"));
            String command = str(map.get("command"));
            if (name == null || command == null) {
                logger.warning("stdio entry missing 'name' or 'command': " + map);
                continue;
            }
            try {
                launch(name, command);
            } catch (Exception e) {
                logger.log(Level.WARNING, "Failed to launch stdio server '" + name + "'", e);
            }
        }
    }

    void onStop(@Observes ShutdownEvent ev) {
        for (StdioProcess p : registry.all()) {
            logger.info("Stopping stdio server: " + p.getName());
            p.destroy();
        }
    }

    private void launch(String name, String command) throws Exception {
        logger.info("Launching stdio MCP server: " + name + " — " + command);

        String[] parts = command.split("\\s+");
        ProcessBuilder pb = new ProcessBuilder(Arrays.asList(parts));
        pb.redirectErrorStream(false);
        Process process = pb.start();

        // Drain stderr in a virtual thread to prevent the process from blocking
        Thread.ofVirtual().name("stdio-stderr-" + name).start(() -> {
            try (var reader = process.errorReader()) {
                String line;
                while ((line = reader.readLine()) != null) {
                    logger.fine("[" + name + " stderr] " + line);
                }
            } catch (Exception ignored) {}
        });

        StdioProcess stdioProcess = new StdioProcess(name, process);

        // MCP initialize handshake
        String initResponse = sendInitialize(stdioProcess);
        logger.info("stdio server '" + name + "' initialized. Response: " + initResponse);

        JsonNode respNode = mapper.readTree(initResponse);
        stdioProcess.setCachedCapabilities(respNode.path("result"));

        // Notify the server that initialization is complete
        sendInitializedNotification(stdioProcess);

        registry.register(stdioProcess);
        logger.info("stdio MCP server registered: " + name);
    }

    private String sendInitialize(StdioProcess proc) throws Exception {
        ObjectNode req = mapper.createObjectNode();
        req.put("jsonrpc", "2.0");
        req.put("id", 1);
        req.put("method", "initialize");
        ObjectNode params = req.putObject("params");
        params.put("protocolVersion", "2024-11-05");
        params.putObject("capabilities");
        ObjectNode clientInfo = params.putObject("clientInfo");
        clientInfo.put("name", "quarkus-mcp-gateway");
        clientInfo.put("version", "1.0.0");
        return proc.request(mapper.writeValueAsString(req));
    }

    private void sendInitializedNotification(StdioProcess proc) throws Exception {
        ObjectNode notif = mapper.createObjectNode();
        notif.put("jsonrpc", "2.0");
        notif.put("method", "notifications/initialized");
        notif.putObject("params");
        proc.request(mapper.writeValueAsString(notif)); // returns null (notification)
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> loadConfig() {
        Yaml yaml = new Yaml();

        Path external = Path.of("servers.yaml");
        if (Files.exists(external)) {
            try (InputStream is = Files.newInputStream(external)) {
                return yaml.load(is);
            } catch (Exception e) {
                logger.log(Level.WARNING, "Failed to read servers.yaml", e);
            }
        }

        try (InputStream is = Thread.currentThread().getContextClassLoader()
                .getResourceAsStream("servers.yaml")) {
            if (is != null) return yaml.load(is);
        } catch (Exception e) {
            logger.log(Level.WARNING, "Failed to read classpath servers.yaml", e);
        }

        return null;
    }

    private static String str(Object obj) {
        return obj != null ? obj.toString() : null;
    }
}
