package com.scivicslab.mcpgateway.registry;

/**
 * CDI event fired when a server is registered or unregistered.
 *
 * @param serverName the server name
 * @param action     "registered" or "unregistered"
 */
public record ServerRegistryEvent(String serverName, String action) {}
