import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpExchange;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URL;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class BenchmarkRunner {

    private static final int UPSTREAM_PORT = 8089;
    private static final int PROXY_PORT = 8088;
    private static final String TARGET_ENDPOINT = "http://localhost:" + UPSTREAM_PORT + "/test-endpoint";

    private static void log(String msg) {
        System.out.println(msg);
        System.out.flush();
    }

    private static void logf(String format, Object... args) {
        System.out.printf(format, args);
        System.out.flush();
    }

    public static void main(String[] args) throws Exception {
        log("==================================================================");
        log("       HTTP PROXY SERVER BENCHMARK & TEST SUITE                   ");
        log("==================================================================");

        // 1. Verify Cache Expiration & LRU Eviction
        testCacheTtlAndLru();

        // 2. Start Mock Upstream Server
        ExecutorService upstreamExecutor = Executors.newFixedThreadPool(8);
        HttpServer upstreamServer = HttpServer.create(new InetSocketAddress(UPSTREAM_PORT), 0);
        upstreamServer.createContext("/test-endpoint", new UpstreamHandler());
        upstreamServer.setExecutor(upstreamExecutor);
        upstreamServer.start();
        log("[+] Upstream Server started on port " + UPSTREAM_PORT);

        // 3. Start Proxy Server
        HttpCache cache = new HttpCache(100, 60_000);
        ProxyServer proxyServer = new ProxyServer(PROXY_PORT, cache);
        Thread proxyThread = new Thread(proxyServer, "ProxyServer-Benchmark");
        proxyThread.setDaemon(true);
        proxyThread.start();
        Thread.sleep(500);
        log("[+] Proxy Server started on port " + PROXY_PORT);

        try {
            // 4. Latency Verification (Miss vs Hit)
            log("\n--- Latency Comparison (Miss vs Hit) ---");
            
            // Request 1: Cache Miss
            long startMiss = System.nanoTime();
            ProxyResponse missResp = fetchViaProxy(TARGET_ENDPOINT, PROXY_PORT);
            long missLatencyMs = (System.nanoTime() - startMiss) / 1_000_000;
            logf("Request #1 (Cache Miss) Latency : %d ms (%d bytes) [X-Cache: %s]%n", 
                 missLatencyMs, missResp != null ? missResp.data.length : 0, 
                 missResp != null ? missResp.xCache : "NONE");

            // Requests 2-51: Cache Hits
            int hitSampleCount = 50;
            long totalHitNano = 0;
            String sampleHitHeader = null;
            for (int i = 0; i < hitSampleCount; i++) {
                long startHit = System.nanoTime();
                ProxyResponse hitResp = fetchViaProxy(TARGET_ENDPOINT, PROXY_PORT);
                totalHitNano += (System.nanoTime() - startHit);
                if (i == 0 && hitResp != null) {
                    sampleHitHeader = hitResp.xCache;
                }
            }
            double avgHitLatencyMs = (totalHitNano / (double) hitSampleCount) / 1_000_000.0;
            logf("Requests #2-#51 (Cache Hits) Avg Latency: %.2f ms [X-Cache: %s]%n", 
                 avgHitLatencyMs, sampleHitHeader != null ? sampleHitHeader : "NONE");

            // 5. Throughput Test
            log("\n--- Concurrent Load & Throughput Test ---");
            int totalRequests = 200;
            int concurrencyLevel = 20;
            ExecutorService clientPool = Executors.newFixedThreadPool(concurrencyLevel);
            CountDownLatch latch = new CountDownLatch(totalRequests);
            AtomicInteger successCount = new AtomicInteger(0);

            long loadStartNano = System.nanoTime();
            for (int i = 0; i < totalRequests; i++) {
                clientPool.submit(() -> {
                    try {
                        ProxyResponse res = fetchViaProxy(TARGET_ENDPOINT, PROXY_PORT);
                        if (res != null && res.data != null && res.data.length > 0) {
                            successCount.incrementAndGet();
                        }
                    } catch (Exception ignored) {
                    } finally {
                        latch.countDown();
                    }
                });
            }

            latch.await(10, TimeUnit.SECONDS);
            long loadTotalDurationMs = (System.nanoTime() - loadStartNano) / 1_000_000;
            clientPool.shutdownNow();

            double throughputRps = loadTotalDurationMs > 0 ? (successCount.get() * 1000.0) / loadTotalDurationMs : 0;

            // Output Metrics Table
            log("\n==================================================================");
            log("                      BENCHMARK RESULTS                           ");
            log("==================================================================");
            logf("%-30s : %d%n", "Total Requests Executed", totalRequests + hitSampleCount + 1);
            logf("%-30s : %d%n", "Concurrency Level", concurrencyLevel);
            logf("%-30s : %d ms%n", "Cache Miss Latency", missLatencyMs);
            logf("%-30s : %.2f ms%n", "Avg Cache Hit Latency", avgHitLatencyMs);
            logf("%-30s : %d / %d%n", "Successful Requests", successCount.get(), totalRequests);
            logf("%-30s : %.2f req/sec%n", "Throughput", throughputRps);
            logf("%-30s : %d%n", "Cache Hits Recorded", cache.getCacheHits());
            logf("%-30s : %d%n", "Cache Misses Recorded", cache.getCacheMisses());
            log("==================================================================");

        } finally {
            proxyServer.stop();
            upstreamServer.stop(0);
            upstreamExecutor.shutdownNow();
            log("\n[+] Benchmark teardown completed.");
        }
    }

    private static void testCacheTtlAndLru() throws Exception {
        log("\n--- Cache TTL & LRU Eviction Unit Test ---");
        
        HttpCache ttlCache = new HttpCache(10, 100);
        ttlCache.put("http://test.com/item1", "Payload1".getBytes());
        assert ttlCache.get("http://test.com/item1") != null;
        log("[+] TTL Initial Hit Verified");
        
        Thread.sleep(150);
        assert ttlCache.get("http://test.com/item1") == null;
        log("[+] TTL Expiration Verified");

        HttpCache lruCache = new HttpCache(2, 60_000);
        lruCache.put("http://test.com/a", "A".getBytes());
        lruCache.put("http://test.com/b", "B".getBytes());
        lruCache.get("http://test.com/a");
        lruCache.put("http://test.com/c", "C".getBytes());
        
        assert lruCache.get("http://test.com/a") != null;
        assert lruCache.get("http://test.com/b") == null;
        assert lruCache.get("http://test.com/c") != null;
        log("[+] LRU Eviction Verified");
    }

    static class ProxyResponse {
        final byte[] data;
        final String xCache;
        ProxyResponse(byte[] data, String xCache) {
            this.data = data;
            this.xCache = xCache;
        }
    }

    private static ProxyResponse fetchViaProxy(String targetUrl, int proxyPort) {
        try {
            URL url = URI.create(targetUrl).toURL();
            HttpURLConnection connection = (HttpURLConnection) url.openConnection(
                    new java.net.Proxy(java.net.Proxy.Type.HTTP, new InetSocketAddress("localhost", proxyPort))
            );
            connection.setConnectTimeout(3000);
            connection.setReadTimeout(3000);
            
            try (InputStream in = connection.getInputStream();
                 ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
                String xCache = connection.getHeaderField("X-Cache");
                byte[] buffer = new byte[4096];
                int n;
                while ((n = in.read(buffer)) != -1) {
                    baos.write(buffer, 0, n);
                }
                return new ProxyResponse(baos.toByteArray(), xCache);
            }
        } catch (Exception e) {
            return null;
        }
    }

    static class UpstreamHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) {
            try {
                Thread.sleep(50);
                byte[] response = "Hello World - Caching Proxy Test Payload!".getBytes();
                exchange.sendResponseHeaders(200, response.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(response);
                }
            } catch (Exception e) {
                exchange.close();
            }
        }
    }
}
