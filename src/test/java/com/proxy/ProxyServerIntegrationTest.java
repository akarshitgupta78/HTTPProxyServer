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

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

class ProxyServerIntegrationTest {

    private static int upstreamPort;
    private static int proxyPort;
    private static HttpServer upstreamHttpServer;
    private static ProxyServer proxyServer;
    private static HttpCache cache;
    private static Thread proxyThread;

    @BeforeAll
    static void setUp() throws Exception {
        // Allocate dynamic ports
        try (ServerSocket s1 = new ServerSocket(0); ServerSocket s2 = new ServerSocket(0)) {
            upstreamPort = s1.getLocalPort();
            proxyPort = s2.getLocalPort();
        }

        // Start Upstream Mock HTTP Server
        upstreamHttpServer = HttpServer.create(new InetSocketAddress(upstreamPort), 0);
        upstreamHttpServer.createContext("/test-get", new HttpHandler() {
            @Override
            public void handle(HttpExchange exchange) {
                try {
                    byte[] response = "Upstream GET Response Body".getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, response.length);
                    try (OutputStream os = exchange.getResponseBody()) {
                        os.write(response);
                    }
                } catch (Exception ignored) {
                }
            }
        });
        upstreamHttpServer.createContext("/test-post", new HttpHandler() {
            @Override
            public void handle(HttpExchange exchange) {
                try {
                    byte[] body = exchange.getRequestBody().readAllBytes();
                    String reply = "Echo: " + new String(body, StandardCharsets.UTF_8);
                    byte[] response = reply.getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, response.length);
                    try (OutputStream os = exchange.getResponseBody()) {
                        os.write(response);
                    }
                } catch (Exception ignored) {
                }
            }
        });
        upstreamHttpServer.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        upstreamHttpServer.start();

        // Start Proxy Server
        cache = new HttpCache(100, 60000);
        proxyServer = new ProxyServer(proxyPort, cache);
        proxyThread = Thread.ofVirtual().name("test-proxy-server").start(proxyServer);
        Thread.sleep(300);
    }

    @AfterAll
    static void tearDown() {
        if (proxyServer != null) {
            proxyServer.stop();
        }
        if (upstreamHttpServer != null) {
            upstreamHttpServer.stop(0);
        }
    }

    @Test
    @DisplayName("Verify Cache Miss on first GET and Cache Hit on subsequent GET")
    void testCacheMissAndHit() throws Exception {
        String targetUrl = "http://localhost:" + upstreamPort + "/test-get";

        // Request 1: Should be MISS
        ProxyResult r1 = fetchViaProxy(targetUrl, proxyPort);
        assertThat(r1.statusCode).isEqualTo(200);
        assertThat(r1.body).isEqualTo("Upstream GET Response Body");
        assertThat(r1.xCache).isEqualTo("MISS");

        // Request 2: Should be HIT
        ProxyResult r2 = fetchViaProxy(targetUrl, proxyPort);
        assertThat(r2.statusCode).isEqualTo(200);
        assertThat(r2.body).isEqualTo("Upstream GET Response Body");
        assertThat(r2.xCache).isEqualTo("HIT");
    }

    @Test
    @DisplayName("Verify HTTPS CONNECT Tunneling forwards raw TCP stream bi-directionally")
    void testConnectTunneling() throws Exception {
        // Start a mock raw TCP echo service on a dynamic port
        int mockTcpPort;
        try (ServerSocket s = new ServerSocket(0)) {
            mockTcpPort = s.getLocalPort();
        }

        ServerSocket echoServer = new ServerSocket(mockTcpPort);
        Thread echoThread = Thread.ofVirtual().start(() -> {
            try (Socket socket = echoServer.accept();
                 BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                 OutputStream out = socket.getOutputStream()) {
                String line = reader.readLine();
                out.write(("ECHO: " + line + "\n").getBytes(StandardCharsets.UTF_8));
                out.flush();
            } catch (Exception ignored) {
            }
        });

        // Client connects to proxy and issues CONNECT request
        try (Socket clientSocket = new Socket("localhost", proxyPort);
             OutputStream clientOut = clientSocket.getOutputStream();
             BufferedReader clientIn = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()))) {

            // Send CONNECT request
            String connectReq = "CONNECT localhost:" + mockTcpPort + " HTTP/1.1\r\nHost: localhost:" + mockTcpPort + "\r\n\r\n";
            clientOut.write(connectReq.getBytes(StandardCharsets.US_ASCII));
            clientOut.flush();

            // Read proxy response: HTTP/1.1 200 Connection Established
            String statusLine = clientIn.readLine();
            assertThat(statusLine).contains("200 Connection Established");

            // Consume remaining headers until empty line
            String h;
            while ((h = clientIn.readLine()) != null && !h.isEmpty()) {
                // Header consumption
            }

            // Send encrypted-like raw payload through tunnel
            clientOut.write("Hello-Through-Tunnel\n".getBytes(StandardCharsets.UTF_8));
            clientOut.flush();

            // Read echo response through tunnel
            String echoReply = clientIn.readLine();
            assertThat(echoReply).isEqualTo("ECHO: Hello-Through-Tunnel");
        } finally {
            echoServer.close();
            echoThread.join(2000);
        }
    }

    private static class ProxyResult {
        final int statusCode;
        final String body;
        final String xCache;

        ProxyResult(int statusCode, String body, String xCache) {
            this.statusCode = statusCode;
            this.body = body;
            this.xCache = xCache;
        }
    }

    private static ProxyResult fetchViaProxy(String targetUrl, int pPort) throws Exception {
        URL url = URI.create(targetUrl).toURL();
        HttpURLConnection conn = (HttpURLConnection) url.openConnection(
            new java.net.Proxy(java.net.Proxy.Type.HTTP, new InetSocketAddress("localhost", pPort))
        );
        conn.setConnectTimeout(3000);
        conn.setReadTimeout(3000);

        int code = conn.getResponseCode();
        String xCache = conn.getHeaderField("X-Cache");
        try (InputStream in = conn.getInputStream();
             ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            byte[] buf = new byte[2048];
            int n;
            while ((n = in.read(buf)) != -1) {
                baos.write(buf, 0, n);
            }
            return new ProxyResult(code, baos.toString(StandardCharsets.UTF_8), xCache);
        }
    }
}

