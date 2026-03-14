package com.scivicslab.mcpgateway.registry;

import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.Config;

import java.util.logging.Logger;

/**
 * Reads gateway.servers.* from application.properties and auto-registers them.
 *
 * Example config:
 *   gateway.servers.coder-agent=http://localhost:8090
 *   gateway.servers.workflow-editor=http://localhost:8081
 */
@ApplicationScoped
public class AutoRegistrar {

    private static final Logger logger = Logger.getLogger(AutoRegistrar.class.getName());
    private static final String PREFIX = "gateway.servers.";

    @Inject
    ServerRegistry registry;

    @Inject
    Config config;

    void onStart(@Observes StartupEvent event) {
        for (String propName : config.getPropertyNames()) {
            if (propName.startsWith(PREFIX)) {
                String serverName = propName.substring(PREFIX.length());
                String url = config.getValue(propName, String.class);
                registry.register(serverName, url, "auto-registered from config");
                logger.info("Auto-registered: " + serverName + " -> " + url);
            }
        }
    }
}
