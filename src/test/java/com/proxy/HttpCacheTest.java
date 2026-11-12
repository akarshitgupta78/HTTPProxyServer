package com.proxy;

import com.proxy.core.HttpCache;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class HttpCacheTest {

    @Test
    @DisplayName("Should put and retrieve unexpired cache entry")
    void testBasicPutAndGet() {
        HttpCache cache = new HttpCache(10, 5000);
        byte[] payload = "Test Payload".getBytes();
        cache.put("http://example.com/item1", payload);

        byte[] result = cache.get("http://example.com/item1");
        assertThat(result).isNotNull();
        assertThat(new String(result)).isEqualTo("Test Payload");
        assertThat(cache.getCacheHits()).isEqualTo(1);
    }

    @Test
    @DisplayName("Should evict entry when TTL expires")
    void testTtlExpiration() throws InterruptedException {
        HttpCache cache = new HttpCache(10, 100); // 100ms TTL
        cache.put("http://example.com/item1", "Data".getBytes());

        assertThat(cache.get("http://example.com/item1")).isNotNull();

        Thread.sleep(150);

        byte[] result = cache.get("http://example.com/item1");
        assertThat(result).isNull();
        assertThat(cache.getCacheMisses()).isEqualTo(1);
    }

    @Test
    @DisplayName("Should evict least-recently-used item when capacity is exceeded")
    void testLruCapacityEviction() {
        HttpCache cache = new HttpCache(2, 60000);
        cache.put("http://example.com/a", "A".getBytes());
        cache.put("http://example.com/b", "B".getBytes());

        // Access "a" to make it more recently used than "b"
        cache.get("http://example.com/a");

        // Insert "c", which should evict "b"
        cache.put("http://example.com/c", "C".getBytes());

        assertThat(cache.get("http://example.com/a")).isNotNull();
        assertThat(cache.get("http://example.com/b")).isNull();
        assertThat(cache.get("http://example.com/c")).isNotNull();
        assertThat(cache.getEvictionCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("Should evict LRU items when memory limit is exceeded")
    void testMemoryLimitEviction() {
        // Cache with max capacity 10, but max memory limit of 100 bytes
        HttpCache cache = new HttpCache(10, 60000, 100);

        byte[] item1 = new byte[40];
        byte[] item2 = new byte[40];
        byte[] item3 = new byte[40];

        cache.put("http://example.com/1", item1);
        cache.put("http://example.com/2", item2);
        // Total bytes = 80, within 100 limit

        cache.put("http://example.com/3", item3);
        // Total bytes would be 120 > 100 limit -> evicts "http://example.com/1"

        assertThat(cache.get("http://example.com/1")).isNull();
        assertThat(cache.get("http://example.com/2")).isNotNull();
        assertThat(cache.get("http://example.com/3")).isNotNull();
        assertThat(cache.getCurrentMemoryBytes()).isLessThanOrEqualTo(100);
    }

    @Test
    @DisplayName("High Concurrency Stress Test: 100 Virtual Threads executing concurrent reads and writes")
    void testConcurrentReadWriteStress() throws InterruptedException {
        int capacity = 50;
        HttpCache cache = new HttpCache(capacity, 10000, 1024 * 1024);
        int threadCount = 100;
        int opsPerThread = 500;

        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);
        AtomicInteger exceptionCount = new AtomicInteger(0);

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < threadCount; i++) {
                final int threadId = i;
                executor.submit(() -> {
                    try {
                        startLatch.await();
                        for (int j = 0; j < opsPerThread; j++) {
                            String key = "http://example.com/item-" + (j % 80);
                            if (threadId % 2 == 0) {
                                cache.put(key, ("Payload-" + j).getBytes());
                            } else {
                                cache.get(key);
                            }
                        }
                    } catch (Exception e) {
                        exceptionCount.incrementAndGet();
                    } finally {
                        doneLatch.countDown();
                    }
                });
            }

            // Release all threads simultaneously to maximize contention
            startLatch.countDown();

            boolean finished = doneLatch.await(15, TimeUnit.SECONDS);
            assertThat(finished).isTrue();
            assertThat(exceptionCount.get()).isEqualTo(0);
            assertThat(cache.size()).isLessThanOrEqualTo(capacity);
        }
    }
}

