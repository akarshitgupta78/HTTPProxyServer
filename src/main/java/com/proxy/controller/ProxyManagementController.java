package com.proxy.controller;

import com.proxy.core.HttpCache;
import com.proxy.core.ProxyServer;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/proxy")
public class ProxyManagementController {

    private final HttpCache cache;
    private final ProxyServer proxyServer;

    public ProxyManagementController(HttpCache cache, ProxyServer proxyServer) {
        this.cache = cache;
        this.proxyServer = proxyServer;
    }

    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> getStats() {
        return ResponseEntity.ok(Map.of(
            "status", proxyServer.isRunning() ? "RUNNING" : "STOPPED",
            "proxyPort", proxyServer.getPort(),
            "activeConnections", proxyServer.getActiveConnections(),
            "totalAcceptedConnections", proxyServer.getTotalAcceptedConnections(),
            "uptimeSeconds", proxyServer.getUptimeSeconds(),
            "cacheHits", cache.getCacheHits(),
            "cacheMisses", cache.getCacheMisses(),
            "hitRatioPercent", Math.round(cache.getHitRatio() * 100.0) / 100.0,
            "cacheEvictions", cache.getEvictionCount(),
            "currentCacheSize", cache.size(),
            "currentMemoryBytes", cache.getCurrentMemoryBytes(),
            "maxCapacity", cache.getMaxCapacity(),
            "maxMemoryBytes", cache.getMaxMemoryBytes()
        ));
    }

    @GetMapping("/cache")
    public ResponseEntity<List<Map<String, Object>>> listCache() {
        return ResponseEntity.ok(cache.listEntries());
    }

    @DeleteMapping("/cache")
    public ResponseEntity<Map<String, Object>> clearCache() {
        cache.clear();
        return ResponseEntity.ok(Map.of(
            "message", "Cache cleared successfully",
            "currentSize", cache.size()
        ));
    }

    @DeleteMapping("/cache/entry")
    public ResponseEntity<Map<String, Object>> evictEntry(@RequestParam("uri") String uri) {
        boolean removed = cache.remove(uri);
        return ResponseEntity.ok(Map.of(
            "uri", uri,
            "removed", removed
        ));
    }

    public static class UpdateConfigRequest {
        public Integer maxCapacity;
        public Long defaultTtlMs;
        public Long maxMemoryBytes;
    }

    @PostMapping("/config")
    public ResponseEntity<Map<String, Object>> updateConfig(@RequestBody UpdateConfigRequest request) {
        int cap = (request.maxCapacity != null) ? request.maxCapacity : cache.getMaxCapacity();
        long ttl = (request.defaultTtlMs != null) ? request.defaultTtlMs : cache.getDefaultTtlMillis();
        long mem = (request.maxMemoryBytes != null) ? request.maxMemoryBytes : cache.getMaxMemoryBytes();

        cache.updateConfig(cap, ttl, mem);

        return ResponseEntity.ok(Map.of(
            "message", "Cache configuration updated",
            "maxCapacity", cache.getMaxCapacity(),
            "defaultTtlMs", cache.getDefaultTtlMillis(),
            "maxMemoryBytes", cache.getMaxMemoryBytes()
        ));
    }
}

