package com.proxy;

import com.proxy.core.HttpCache;
import com.proxy.core.ProxyServer;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

public class BenchmarkStressTest {

    private static int upstreamPort;
    private static int proxyPort;
    private static HttpServer upstreamServer;
    private static ProxyServer proxyServer;
    private static HttpCache cache;
    private static String targetUrl;

    @BeforeAll
    static void setUp() throws Exception {
        try (ServerSocket s1 = new ServerSocket(0); ServerSocket s2 = new ServerSocket(0)) {
            upstreamPort = s1.getLocalPort();
            proxyPort = s2.getLocalPort();
        }

        targetUrl = "http://localhost:" + upstreamPort + "/bench-target";

        // Upstream server with 30ms simulated processing delay
        upstreamServer = HttpServer.create(new InetSocketAddress(upstreamPort), 0);
        upstreamServer.createContext("/bench-target", new HttpHandler() {
            @Override
            public void handle(HttpExchange exchange) {
                try {
                    Thread.sleep(30);
                    byte[] response = "BenchPayloadData-1234567890".getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, response.length);
                    try (OutputStream os = exchange.getResponseBody()) {
                        os.write(response);
                    }
                } catch (Exception ignored) {
                }
            }
        });
        upstreamServer.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        upstreamServer.start();

        cache = new HttpCache(500, 60000);
        proxyServer = new ProxyServer(proxyPort, cache);
        Thread.ofVirtual().name("bench-proxy").start(proxyServer);
        Thread.sleep(300);
    }

    @AfterAll
    static void tearDown() {
        if (proxyServer != null) {
            proxyServer.stop();
        }
        if (upstreamServer != null) {
            upstreamServer.stop(0);
        }
    }

    @Test
    @DisplayName("Reproducible Benchmark: JIT Warmup, Percentiles (p50, p90, p99), and Throughput")
    void runReproducibleBenchmark() throws Exception {
        System.out.println("==================================================================");
        System.out.println("       REPRODUCIBLE BENCHMARK & CONCURRENCY VERIFICATION          ");
        System.out.println("==================================================================");

        // 1. Initial Cache Miss
        long missStart = System.nanoTime();
        BenchmarkResponse missResp = fetch(targetUrl, proxyPort);
        long missDurationMs = (System.nanoTime() - missStart) / 1_000_000;
        assertThat(missResp.xCache).isEqualTo("MISS");
        System.out.printf("[+] Cache Miss Latency : %d ms [X-Cache: %s]%n", missDurationMs, missResp.xCache);

        // 2. JVM Warmup Phase (200 requests to trigger HotSpot JIT compilation)
        System.out.print("[+] Executing JVM Warmup (200 iterations)... ");
        for (int i = 0; i < 200; i++) {
            fetch(targetUrl, proxyPort);
        }
        System.out.println("Done.");

        // 3. Measured Cache Hit Latency with Statistical Percentiles
        int hitSamples = 500;
        long[] hitLatenciesNano = new long[hitSamples];
        for (int i = 0; i < hitSamples; i++) {
            long s = System.nanoTime();
            BenchmarkResponse r = fetch(targetUrl, proxyPort);
            hitLatenciesNano[i] = System.nanoTime() - s;
            assertThat(r.xCache).isEqualTo("HIT");
        }

        Arrays.sort(hitLatenciesNano);
        double minMs = hitLatenciesNano[0] / 1_000_000.0;
        double p50Ms = hitLatenciesNano[(int) (hitSamples * 0.50)] / 1_000_000.0;
        double p90Ms = hitLatenciesNano[(int) (hitSamples * 0.90)] / 1_000_000.0;
        double p95Ms = hitLatenciesNano[(int) (hitSamples * 0.95)] / 1_000_000.0;
        double p99Ms = hitLatenciesNano[(int) (hitSamples * 0.99)] / 1_000_000.0;
        double maxMs = hitLatenciesNano[hitSamples - 1] / 1_000_000.0;

        System.out.println("\n--- Post-Warmup Cache Hit Percentiles (500 Samples) ---");
        System.out.printf("  Min Latency : %.3f ms%n", minMs);
        System.out.printf("  p50 (Median): %.3f ms%n", p50Ms);
        System.out.printf("  p90         : %.3f ms%n", p90Ms);
        System.out.printf("  p95         : %.3f ms%n", p95Ms);
        System.out.printf("  p99         : %.3f ms%n", p99Ms);
        System.out.printf("  Max Latency : %.3f ms%n", maxMs);

        // 4. Concurrent Load & Throughput Stress Test
        int concurrentRequests = 500;
        int virtualWorkers = 50;
        CountDownLatch latch = new CountDownLatch(concurrentRequests);
        AtomicInteger successfulRequests = new AtomicInteger(0);

        long loadStartNano = System.nanoTime();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < concurrentRequests; i++) {
                executor.submit(() -> {
                    try {
                        BenchmarkResponse res = fetch(targetUrl, proxyPort);
                        if (res != null && "HIT".equals(res.xCache)) {
                            successfulRequests.incrementAndGet();
                        }
                    } catch (Exception ignored) {
                    } finally {
                        latch.countDown();
                    }
                });
            }

            boolean finished = latch.await(10, TimeUnit.SECONDS);
            long totalLoadDurationMs = (System.nanoTime() - loadStartNano) / 1_000_000;

            double throughputRps = totalLoadDurationMs > 0 ? (successfulRequests.get() * 1000.0) / totalLoadDurationMs : 0;

            System.out.println("\n--- High-Concurrency Load Test Results ---");
            System.out.printf("  Total Requests       : %d%n", concurrentRequests);
            System.out.printf("  Successful Hits      : %d / %d (%.1f%%)%n", 
                successfulRequests.get(), concurrentRequests, 
                (successfulRequests.get() * 100.0) / concurrentRequests);
            System.out.printf("  Total Duration       : %d ms%n", totalLoadDurationMs);
            System.out.printf("  Measured Throughput  : %.2f requests/sec%n", throughputRps);
            System.out.println("==================================================================");

            assertThat(finished).isTrue();
            assertThat(successfulRequests.get()).isEqualTo(concurrentRequests);
        }
    }

    private static class BenchmarkResponse {
        final byte[] data;
        final String xCache;
        BenchmarkResponse(byte[] data, String xCache) {
            this.data = data;
            this.xCache = xCache;
        }
    }

    private static BenchmarkResponse fetch(String target, int pPort) throws Exception {
        URL url = URI.create(target).toURL();
        HttpURLConnection conn = (HttpURLConnection) url.openConnection(
            new java.net.Proxy(java.net.Proxy.Type.HTTP, new InetSocketAddress("localhost", pPort))
        );
        conn.setConnectTimeout(3000);
        conn.setReadTimeout(3000);

        String xCache = conn.getHeaderField("X-Cache");
        try (InputStream in = conn.getInputStream();
             ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            byte[] buf = new byte[2048];
            int n;
            while ((n = in.read(buf)) != -1) {
                baos.write(buf, 0, n);
            }
            return new BenchmarkResponse(baos.toByteArray(), xCache);
        }
    }
}

