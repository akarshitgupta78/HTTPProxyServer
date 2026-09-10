import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public class HttpCache {

    public static class CacheEntry {
        private final byte[] responseData;
        private final long createdAtMillis; 
        private final long ttlMillis; 

        public CacheEntry(byte[] responseData, long ttlMillis) {
            this.responseData = responseData;
            this.createdAtMillis = System.currentTimeMillis();
            this.ttlMillis = ttlMillis;
        }

        public byte[] getResponseData() {
            return responseData;
        }

        public boolean isExpired() {
            return System.currentTimeMillis() - createdAtMillis > ttlMillis;
        }
    }

    private final int maxCapacity;
    private final long defaultTtlMillis;
    private final Map<String, CacheEntry> cacheMap;
    
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private final ReentrantReadWriteLock.ReadLock readLock = lock.readLock();
    private final ReentrantReadWriteLock.WriteLock writeLock = lock.writeLock();

    private final AtomicLong cacheHits = new AtomicLong(0);
    private final AtomicLong cacheMisses = new AtomicLong(0);

    public HttpCache(int maxCapacity, long defaultTtlMillis) {
        this.maxCapacity = maxCapacity;
        this.defaultTtlMillis = defaultTtlMillis;
        // accessOrder = true enables LRU eviction order
        this.cacheMap = new LinkedHashMap<>(maxCapacity, 0.75f, true);
    }

    public HttpCache() {
        this(100, 60_000);
    }

    public byte[] get(String uri) {
        readLock.lock();
        CacheEntry entry;
        try {
            entry = cacheMap.get(uri);
        } finally {
            readLock.unlock();
        }

        if (entry == null) {
            cacheMisses.incrementAndGet();
            return null;
        }

        if (entry.isExpired()) {
            writeLock.lock();
            try {
                CacheEntry cur = cacheMap.get(uri);
                if (cur != null && cur.isExpired()) {
                    cacheMap.remove(uri);
                }
            } finally {
                writeLock.unlock();
            }
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
        writeLock.lock();
        try {
            cacheMap.put(uri, new CacheEntry(responseData, ttlMillis));

            if (cacheMap.size() > maxCapacity) {
                String eldestKey = cacheMap.keySet().iterator().next();
                cacheMap.remove(eldestKey);
            }
        } finally {
            writeLock.unlock();
        }
    }

    public int size() {
        readLock.lock();
        try {
            return cacheMap.size();
        } finally {
            readLock.unlock();
        }
    }

    public void clear() {
        writeLock.lock();
        try {
            cacheMap.clear();
        } finally {
            writeLock.unlock();
        }
    }

    public long getCacheHits() {
        return cacheHits.get();
    }

    public long getCacheMisses() {
        return cacheMisses.get();
    }
}
