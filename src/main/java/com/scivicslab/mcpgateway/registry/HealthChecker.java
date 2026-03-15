package com.scivicslab.mcpgateway.registry;

import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.net.HttpURLConnection;
import java.net.URI;
import java.time.Instant;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Periodically checks health of registered MCP servers
 * by sending an HTTP GET to their base URL.
 */
@ApplicationScoped
public class HealthChecker {

    private static final Logger logger = Logger.getLogger(HealthChecker.class.getName());
    private static final int TIMEOUT_MS = 3000;

    @Inject
    ServerRegistry registry;

    /**
     * Check a single server's health. Returns true if reachable.
     */
    public boolean check(ServerEntry entry) {
        boolean reachable = probe(entry.getUrl());
        entry.setHealthy(reachable);
        entry.setLastHealthCheck(Instant.now());
        return reachable;
    }

    /**
     * Check all registered servers. Runs every 30 seconds.
     */
    @Scheduled(every = "30s", delayed = "5s")
    void checkAll() {
        for (ServerEntry entry : registry.listAll()) {
            boolean was = entry.isHealthy();
            boolean now = check(entry);
            if (was != now) {
                logger.info("Server " + entry.getName() + " is now " + (now ? "healthy" : "down"));
            }
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
