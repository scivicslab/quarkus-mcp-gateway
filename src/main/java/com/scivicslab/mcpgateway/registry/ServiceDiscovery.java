package com.scivicslab.mcpgateway.registry;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Scans configured host:port ranges for MCP servers by sending
 * a JSON-RPC "initialize" request to each candidate endpoint.
 */
@ApplicationScoped
public class ServiceDiscovery {

    private static final Logger logger = Logger.getLogger(ServiceDiscovery.class.getName());
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(3);
    private static final ObjectMapper mapper = new ObjectMapper();

    private static final String INITIALIZE_REQUEST = """
            {
              "jsonrpc": "2.0",
              "id": 1,
              "method": "initialize",
              "params": {
                "protocolVersion": "2024-11-05",
                "capabilities": {},
                "clientInfo": {
                  "name": "mcp-gateway-discovery",
                  "version": "1.0.0"
                }
              }
            }
            """;

    @Inject
    ServerRegistry registry;

    @Inject
    HealthChecker healthChecker;

    /**
     * Result of probing a single endpoint.
     */
    public record DiscoveredServer(
            String host,
            int port,
            String name,
            String version,
            String url,
            boolean alreadyRegistered
    ) {}

    /**
     * Result of a discovery scan.
     */
    public record DiscoveryResult(
            List<DiscoveredServer> discovered,
            int scannedCount,
            long elapsedMs
    ) {}

    /**
     * Scan request from the UI or API.
     */
    public record ScanRequest(String host, String ports) {}

    /**
     * Scan with explicit host/ports parameters (from UI).
     * Falls back to discovery.yaml / defaults if parameters are null.
     */
    public DiscoveryResult scan(String host, String ports) {
        List<Map.Entry<String, Integer>> candidates;
        if (host != null && !host.isBlank() && ports != null && !ports.isBlank()) {
            candidates = buildCandidates(host.trim(), ports.trim());
        } else {
            candidates = loadCandidates();
        }

        if (candidates.isEmpty()) {
            return new DiscoveryResult(List.of(), 0, 0);
        }

        long start = System.currentTimeMillis();
        List<DiscoveredServer> discovered = probeAll(candidates);
        long elapsed = System.currentTimeMillis() - start;

        logger.info("Discovery scan complete: " + discovered.size() + " servers found out of "
                + candidates.size() + " candidates in " + elapsed + "ms");

        return new DiscoveryResult(discovered, candidates.size(), elapsed);
    }

    /**
     * Scan using discovery.yaml / defaults.
     */
    public DiscoveryResult scan() {
        return scan(null, null);
    }

    /**
     * Register all given discovered servers into the registry.
     * Returns the list of newly registered server entries.
     */
    public List<ServerEntry> registerAll(List<DiscoveredServer> servers) {
        List<ServerEntry> registered = new ArrayList<>();
        for (DiscoveredServer ds : servers) {
            String description = ds.version() != null ? ds.name() + " v" + ds.version() : ds.name();
            ServerEntry entry = registry.register(ds.name(), ds.url(), description);
            entry.setDiscovered(true);
            healthChecker.check(entry);
            registered.add(entry);
            logger.info("Discovery registered: " + ds.name() + " -> " + ds.url());
        }
        return registered;
    }

    private List<DiscoveredServer> probeAll(List<Map.Entry<String, Integer>> candidates) {
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        List<Future<DiscoveredServer>> futures = new ArrayList<>();

        for (var candidate : candidates) {
            futures.add(executor.submit(() -> probe(candidate.getKey(), candidate.getValue())));
        }

        List<DiscoveredServer> results = new ArrayList<>();
        for (var future : futures) {
            try {
                DiscoveredServer result = future.get(REQUEST_TIMEOUT.toMillis() + 2000, TimeUnit.MILLISECONDS);
                if (result != null) {
                    results.add(result);
                }
            } catch (Exception e) {
                // Timeout or execution error — skip
            }
        }

        executor.shutdown();
        return results;
    }

    /**
     * Probe a single host:port for an MCP server.
     * Returns null if not an MCP server or unreachable.
     */
    private DiscoveredServer probe(String host, int port) {
        String baseUrl = "http://" + host + ":" + port;
        String mcpUrl = baseUrl + "/mcp";

        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(CONNECT_TIMEOUT)
                    .build();

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(mcpUrl))
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json, text/event-stream")
                    .timeout(REQUEST_TIMEOUT)
                    .POST(HttpRequest.BodyPublishers.ofString(INITIALIZE_REQUEST))
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                return parseInitializeResponse(host, port, baseUrl, response.body());
            }
        } catch (Exception e) {
            logger.log(Level.FINEST, "Probe failed for " + host + ":" + port, e);
        }
        return null;
    }

    private DiscoveredServer parseInitializeResponse(String host, int port, String baseUrl, String body) {
        try {
            JsonNode root = mapper.readTree(body);

            // Check for valid JSON-RPC response with result.serverInfo
            JsonNode result = root.get("result");
            if (result == null) return null;

            JsonNode serverInfo = result.get("serverInfo");
            if (serverInfo == null) return null;

            String baseName = serverInfo.has("name") ? serverInfo.get("name").asText() : "mcp";
            // Append port to ensure uniqueness, but skip if baseName already ends with "-{port}".
            String portSuffix = "-" + port;
            String name = baseName.endsWith(portSuffix) ? baseName : baseName + portSuffix;
            String version = serverInfo.has("version") ? serverInfo.get("version").asText() : null;

            boolean alreadyRegistered = registry.lookup(name).isPresent();

            logger.info("Discovered MCP server at " + host + ":" + port + " -> " + name
                    + (version != null ? " v" + version : ""));

            return new DiscoveredServer(host, port, name, version, baseUrl, alreadyRegistered);
        } catch (Exception e) {
            logger.log(Level.FINE, "Failed to parse initialize response from " + host + ":" + port, e);
            return null;
        }
    }

    /**
     * Default scan range when no discovery.yaml is present.
     */
    private static final String DEFAULT_HOST = "localhost";
    private static final String DEFAULT_PORTS = "28000-29000";

    /**
     * Load scan candidates from discovery.yaml, falling back to default range.
     */
    @SuppressWarnings("unchecked")
    private List<Map.Entry<String, Integer>> loadCandidates() {
        Map<String, Object> config = loadConfig();

        if (config == null) {
            logger.info("No discovery.yaml found — using default range " + DEFAULT_HOST + ":" + DEFAULT_PORTS);
            return buildCandidates(DEFAULT_HOST, DEFAULT_PORTS);
        }

        Object discoveryObj = config.get("discovery");
        if (!(discoveryObj instanceof Map<?, ?> discovery)) {
            return buildCandidates(DEFAULT_HOST, DEFAULT_PORTS);
        }

        Object targetsObj = discovery.get("targets");
        if (!(targetsObj instanceof List<?> targets)) {
            return buildCandidates(DEFAULT_HOST, DEFAULT_PORTS);
        }

        List<Map.Entry<String, Integer>> candidates = new ArrayList<>();

        for (Object t : targets) {
            if (t instanceof Map<?, ?> target) {
                String host = target.get("host") != null ? target.get("host").toString() : DEFAULT_HOST;
                String portsStr = target.get("ports") != null ? target.get("ports").toString() : DEFAULT_PORTS;
                candidates.addAll(buildCandidates(host, portsStr));
            }
        }

        if (candidates.isEmpty()) {
            return buildCandidates(DEFAULT_HOST, DEFAULT_PORTS);
        }

        logger.info("Discovery candidates: " + candidates.size() + " endpoints to probe");
        return candidates;
    }

    private List<Map.Entry<String, Integer>> buildCandidates(String host, String portsStr) {
        List<Map.Entry<String, Integer>> candidates = new ArrayList<>();
        for (int port : parsePorts(portsStr)) {
            candidates.add(Map.entry(host, port));
        }
        logger.info("Discovery candidates: " + host + ":" + portsStr + " (" + candidates.size() + " ports)");
        return candidates;
    }

    /**
     * Parse a ports string into a list of port numbers.
     * Supports: "8080", "8080-8099", "8080,8090,8091", "8080-8085,8090"
     */
    static List<Integer> parsePorts(String portsStr) {
        List<Integer> ports = new ArrayList<>();
        for (String segment : portsStr.split(",")) {
            segment = segment.trim();
            if (segment.contains("-")) {
                String[] range = segment.split("-", 2);
                int start = Integer.parseInt(range[0].trim());
                int end = Integer.parseInt(range[1].trim());
                for (int p = start; p <= end; p++) {
                    ports.add(p);
                }
            } else {
                ports.add(Integer.parseInt(segment));
            }
        }
        return ports;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> loadConfig() {
        Yaml yaml = new Yaml();

        // 1. Try external file
        Path external = Path.of("discovery.yaml");
        if (Files.exists(external)) {
            try (InputStream is = Files.newInputStream(external)) {
                logger.info("Loading discovery config from: " + external.toAbsolutePath());
                return yaml.load(is);
            } catch (Exception e) {
                logger.log(Level.WARNING, "Failed to read external discovery.yaml", e);
            }
        }

        // 2. Fall back to classpath
        try (InputStream is = Thread.currentThread().getContextClassLoader()
                .getResourceAsStream("discovery.yaml")) {
            if (is != null) {
                logger.info("Loading discovery config from classpath:discovery.yaml");
                return yaml.load(is);
            }
        } catch (Exception e) {
            logger.log(Level.WARNING, "Failed to read classpath discovery.yaml", e);
        }

        return null;
    }
}
