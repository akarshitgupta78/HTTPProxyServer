package com.proxy.config;

import com.proxy.core.HttpCache;
import com.proxy.core.ProxyServer;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "proxy")
public class ProxyConfig {

    private int port = 8000;
    private CacheConfig cache = new CacheConfig();
    private int connectTimeoutMs = 5000;
    private int readTimeoutMs = 5000;

    public static class CacheConfig {
        private int maxCapacity = 500;
        private long defaultTtlMs = 60000;
        private long maxMemoryBytes = 50 * 1024 * 1024L;

        public int getMaxCapacity() {
            return maxCapacity;
        }

        public void setMaxCapacity(int maxCapacity) {
            this.maxCapacity = maxCapacity;
        }

        public long getDefaultTtlMs() {
            return defaultTtlMs;
        }

        public void setDefaultTtlMs(long defaultTtlMs) {
            this.defaultTtlMs = defaultTtlMs;
        }

        public long getMaxMemoryBytes() {
            return maxMemoryBytes;
        }

        public void setMaxMemoryBytes(long maxMemoryBytes) {
            this.maxMemoryBytes = maxMemoryBytes;
        }
    }

    @Bean
    public HttpCache httpCache() {
        return new HttpCache(cache.getMaxCapacity(), cache.getDefaultTtlMs(), cache.getMaxMemoryBytes());
    }

    @Bean
    public ProxyServer proxyServer(HttpCache cache) {
        return new ProxyServer(port, cache);
    }

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public CacheConfig getCache() {
        return cache;
    }

    public void setCache(CacheConfig cache) {
        this.cache = cache;
    }

    public int getConnectTimeoutMs() {
        return connectTimeoutMs;
    }

    public void setConnectTimeoutMs(int connectTimeoutMs) {
        this.connectTimeoutMs = connectTimeoutMs;
    }

    public int getReadTimeoutMs() {
        return readTimeoutMs;
    }

    public void setReadTimeoutMs(int readTimeoutMs) {
        this.readTimeoutMs = readTimeoutMs;
    }
}

