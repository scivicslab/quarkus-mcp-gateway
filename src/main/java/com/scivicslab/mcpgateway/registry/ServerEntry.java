package com.scivicslab.mcpgateway.registry;

import java.time.Instant;

/**
 * A registered MCP server entry.
 */
public class ServerEntry {

    private String name;
    private String url;
    private String description;
    private Instant registeredAt;
    private Instant lastHealthCheck;
    private boolean healthy;
    private Instant unhealthySince;
    private boolean discovered;

    public ServerEntry() {}

    public ServerEntry(String name, String url, String description) {
        this.name = name;
        this.url = url;
        this.description = description;
        this.registeredAt = Instant.now();
        this.healthy = false; // unknown until checked
        this.unhealthySince = null;
        this.discovered = false;
    }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public Instant getRegisteredAt() { return registeredAt; }
    public void setRegisteredAt(Instant registeredAt) { this.registeredAt = registeredAt; }

    public Instant getLastHealthCheck() { return lastHealthCheck; }
    public void setLastHealthCheck(Instant lastHealthCheck) { this.lastHealthCheck = lastHealthCheck; }

    public boolean isHealthy() { return healthy; }
    public void setHealthy(boolean healthy) { this.healthy = healthy; }

    public Instant getUnhealthySince() { return unhealthySince; }
    public void setUnhealthySince(Instant unhealthySince) { this.unhealthySince = unhealthySince; }

    public boolean isDiscovered() { return discovered; }
    public void setDiscovered(boolean discovered) { this.discovered = discovered; }
}
