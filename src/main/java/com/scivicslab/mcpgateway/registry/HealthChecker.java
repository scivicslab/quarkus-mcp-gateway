package com.scivicslab.mcpgateway.registry;

import com.scivicslab.mcpgateway.tools.ToolAggregator;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.net.HttpURLConnection;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Periodically checks health of registered MCP servers
 * by sending an HTTP GET to their base URL.
 * Removes discovered servers that remain unhealthy for over 1 hour.
 */
@ApplicationScoped
public class HealthChecker {

    private static final Logger logger = Logger.getLogger(HealthChecker.class.getName());
    private static final int TIMEOUT_MS = 3000;
    private static final Duration UNHEALTHY_EXPIRY = Duration.ofHours(1);

    @Inject
    ServerRegistry registry;

    @Inject
    ToolAggregator toolAggregator;

    /**
     * Check a single server's health. Returns true if reachable.
     */
    public boolean check(ServerEntry entry) {
        boolean reachable = probe(entry.getUrl());
        entry.setHealthy(reachable);
        entry.setLastHealthCheck(Instant.now());
        if (reachable) {
            entry.setUnhealthySince(null);
        } else if (entry.getUnhealthySince() == null) {
            entry.setUnhealthySince(Instant.now());
        }
        return reachable;
    }

    /**
     * Check all registered servers. Runs every 30 seconds.
     * Removes discovered servers that have been unhealthy for over 1 hour.
     */
    @Scheduled(every = "30s", delayed = "5s")
    void checkAll() {
        Instant now = Instant.now();
        List<String> toRemove = new ArrayList<>();
        for (ServerEntry entry : registry.listAll()) {
            boolean was = entry.isHealthy();
            boolean healthy = check(entry);
            if (was != healthy) {
                logger.info("Server " + entry.getName() + " is now " + (healthy ? "healthy" : "down"));
                if (healthy) {
                    // Server came back up: refresh its tools
                    toolAggregator.refreshServer(entry.getName());
                }
            }
            if (!healthy && entry.isDiscovered()
                    && entry.getUnhealthySince() != null
                    && Duration.between(entry.getUnhealthySince(), now).compareTo(UNHEALTHY_EXPIRY) >= 0) {
                toRemove.add(entry.getName());
            }
        }
        for (String name : toRemove) {
            registry.unregister(name);
            logger.info("Removed unreachable discovered server: " + name
                    + " (unhealthy for over " + UNHEALTHY_EXPIRY.toHours() + " hour(s))");
        }
    }

    private boolean probe(String url) {
        try {
            HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
            int code = conn.getResponseCode();
            conn.disconnect();
            return code >= 200 && code < 500;
        } catch (Exception e) {
            logger.log(Level.FINE, "Health check failed for " + url, e);
            return false;
        }
    }
}
