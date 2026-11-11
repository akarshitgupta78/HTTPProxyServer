package com.proxy.actuator;

import com.proxy.core.HttpCache;
import com.proxy.core.ProxyServer;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

@Component
public class ProxyHealthIndicator implements HealthIndicator {

    private final ProxyServer proxyServer;
    private final HttpCache cache;

    public ProxyHealthIndicator(ProxyServer proxyServer, HttpCache cache) {
        this.proxyServer = proxyServer;
        this.cache = cache;
    }

    @Override
    public Health health() {
        if (!proxyServer.isRunning()) {
            return Health.down()
                .withDetail("proxyPort", proxyServer.getPort())
                .withDetail("error", "Proxy server socket loop is not running")
                .build();
        }

        return Health.up()
            .withDetail("proxyPort", proxyServer.getPort())
            .withDetail("activeConnections", proxyServer.getActiveConnections())
            .withDetail("cacheSize", cache.size())
            .withDetail("cacheHitRatio", String.format("%.2f%%", cache.getHitRatio()))
            .withDetail("uptimeSeconds", proxyServer.getUptimeSeconds())
            .build();
    }
}

