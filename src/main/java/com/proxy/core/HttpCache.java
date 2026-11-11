package com.proxy.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Thread-safe In-Memory HTTP Cache with LRU Eviction, TTL Expiration,
 * Memory Bound Tracking, and Metric Diagnostics.
 */
public class HttpCache {

    public static class CacheEntry {
        private final String uri;
        private final byte[] responseData;
        private final long createdAtMillis;
        private final long ttlMillis;
        private final long sizeBytes;
        private final AtomicLong hits = new AtomicLong(0);
        private volatile long lastAccessedAtMillis;

        public CacheEntry(String uri, byte[] responseData, long ttlMillis) {
            this.uri = uri;
            this.responseData = responseData;
            this.createdAtMillis = System.currentTimeMillis();
            this.lastAccessedAtMillis = this.createdAtMillis;
            this.ttlMillis = ttlMillis;
            this.sizeBytes = responseData != null ? responseData.length : 0L;
        }

        public String getUri() {
            return uri;
        }

        public byte[] getResponseData() {
            return responseData;
        }

        public long getCreatedAtMillis() {
            return createdAtMillis;
        }

        public long getLastAccessedAtMillis() {
            return lastAccessedAtMillis;
        }

        public long getTtlMillis() {
            return ttlMillis;
        }

        public long getSizeBytes() {
            return sizeBytes;
        }

        public long getHits() {
            return hits.get();
        }

        public boolean isExpired() {
            return (System.currentTimeMillis() - createdAtMillis) > ttlMillis;
        }

        public void recordAccess() {
            this.lastAccessedAtMillis = System.currentTimeMillis();
            this.hits.incrementAndGet();
        }
    }

    private volatile int maxCapacity;
    private volatile long defaultTtlMillis;
    private volatile long maxMemoryBytes;

    private final Map<String, CacheEntry> cacheMap;
    private final ReentrantLock lock = new ReentrantLock();

    private final AtomicLong cacheHits = new AtomicLong(0);
    private final AtomicLong cacheMisses = new AtomicLong(0);
    private final AtomicLong evictionCount = new AtomicLong(0);
    private final AtomicLong currentMemoryBytes = new AtomicLong(0);

    public HttpCache(int maxCapacity, long defaultTtlMillis, long maxMemoryBytes) {
        this.maxCapacity = Math.max(1, maxCapacity);
        this.defaultTtlMillis = Math.max(100, defaultTtlMillis);
        this.maxMemoryBytes = Math.max(1024, maxMemoryBytes);
        // accessOrder = true enables LRU eviction order
        this.cacheMap = new LinkedHashMap<>(maxCapacity, 0.75f, true);
    }

    public HttpCache(int maxCapacity, long defaultTtlMillis) {
        this(maxCapacity, defaultTtlMillis, 50 * 1024 * 1024L); // 50 MB default
    }

    public HttpCache() {
        this(500, 60_000L);
    }

    /**
     * Retrieves an entry from cache. Protected by an exclusive lock because
     * access-order LinkedHashMap mutates internal pointers on get().
     */
    public byte[] get(String uri) {
        CacheEntry entry;
        lock.lock();
        try {
            entry = cacheMap.get(uri);
            if (entry != null) {
                if (entry.isExpired()) {
                    cacheMap.remove(uri);
                    currentMemoryBytes.addAndGet(-entry.getSizeBytes());
                    evictionCount.incrementAndGet();
                    entry = null;
                } else {
                    entry.recordAccess();
                }
            }
        } finally {
            lock.unlock();
        }

        if (entry == null) {
            cacheMisses.incrementAndGet();
            return null;
        }

        cacheHits.incrementAndGet();
        return entry.getResponseData();
    }

    public void put(String uri, byte[] responseData) {
        put(uri, responseData, defaultTtlMillis);
    }

    public void put(String uri, byte[] responseData, long ttlMillis) {
        if (responseData == null || uri == null) {
            return;
        }

        long entrySize = responseData.length;
        // Do not cache individual items that exceed 50% of total memory budget
        if (entrySize > (maxMemoryBytes / 2)) {
            return;
        }

        lock.lock();
        try {
            // Remove existing entry for key if present
            CacheEntry old = cacheMap.remove(uri);
            if (old != null) {
                currentMemoryBytes.addAndGet(-old.getSizeBytes());
            }

            CacheEntry newEntry = new CacheEntry(uri, responseData, ttlMillis);
            cacheMap.put(uri, newEntry);
            currentMemoryBytes.addAndGet(entrySize);

            // Enforce LRU eviction based on item count and total memory size
            while (!cacheMap.isEmpty() && (cacheMap.size() > maxCapacity || currentMemoryBytes.get() > maxMemoryBytes)) {
                String eldestKey = cacheMap.keySet().iterator().next();
                CacheEntry evicted = cacheMap.remove(eldestKey);
                if (evicted != null) {
                    currentMemoryBytes.addAndGet(-evicted.getSizeBytes());
                    evictionCount.incrementAndGet();
                }
            }
        } finally {
            lock.unlock();
        }
    }

    public boolean remove(String uri) {
        lock.lock();
        try {
            CacheEntry removed = cacheMap.remove(uri);
            if (removed != null) {
                currentMemoryBytes.addAndGet(-removed.getSizeBytes());
                return true;
            }
            return false;
        } finally {
            lock.unlock();
        }
    }

    public void clear() {
        lock.lock();
        try {
            cacheMap.clear();
            currentMemoryBytes.set(0);
        } finally {
            lock.unlock();
        }
    }

    public int size() {
        lock.lock();
        try {
            return cacheMap.size();
        } finally {
            lock.unlock();
        }
    }

    public List<Map<String, Object>> listEntries() {
        lock.lock();
        try {
            List<Map<String, Object>> list = new ArrayList<>();
            long now = System.currentTimeMillis();
            for (CacheEntry entry : cacheMap.values()) {
                long remainingTtl = Math.max(0, entry.getTtlMillis() - (now - entry.getCreatedAtMillis()));
                list.add(Map.of(
                    "uri", entry.getUri(),
                    "sizeBytes", entry.getSizeBytes(),
                    "hits", entry.getHits(),
                    "remainingTtlMs", remainingTtl,
                    "isExpired", entry.isExpired()
                ));
            }
            return Collections.unmodifiableList(list);
        } finally {
            lock.unlock();
        }
    }

    public void updateConfig(int maxCapacity, long defaultTtlMillis, long maxMemoryBytes) {
        lock.lock();
        try {
            this.maxCapacity = Math.max(1, maxCapacity);
            this.defaultTtlMillis = Math.max(100, defaultTtlMillis);
            this.maxMemoryBytes = Math.max(1024, maxMemoryBytes);
        } finally {
            lock.unlock();
        }
    }

    public long getCacheHits() {
        return cacheHits.get();
    }

    public long getCacheMisses() {
        return cacheMisses.get();
    }

    public long getEvictionCount() {
        return evictionCount.get();
    }

    public long getCurrentMemoryBytes() {
        return currentMemoryBytes.get();
    }

    public int getMaxCapacity() {
        return maxCapacity;
    }

    public long getDefaultTtlMillis() {
        return defaultTtlMillis;
    }

    public long getMaxMemoryBytes() {
        return maxMemoryBytes;
    }

    public double getHitRatio() {
        long hits = cacheHits.get();
        long total = hits + cacheMisses.get();
        return total > 0 ? ((double) hits / total) * 100.0 : 0.0;
    }
}

