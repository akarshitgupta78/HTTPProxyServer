package com.proxy.core;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Worker Runnable dispatched per client connection.
 * Supports:
 * 1. HTTP Forward Proxying with in-memory caching for GET requests (respecting Cache-Control).
 * 2. HTTPS CONNECT Tunneling with bi-directional streaming.
 * 3. Transparent pass-through for non-GET methods (POST, PUT, DELETE, etc.).
 * 4. SSRF protection against sensitive cloud metadata endpoints.
 */
public class ClientHandler implements Runnable {

    private static final int SOCKET_TIMEOUT_MS = 10_000;
    private static final int BUFFER_SIZE = 16_384;
    private static final Pattern MAX_AGE_PATTERN = Pattern.compile("max-age=(\\d+)", Pattern.CASE_INSENSITIVE);

    private static final Set<String> HOP_BY_HOP_HEADERS = Set.of(
        "connection", "proxy-connection", "keep-alive", 
        "transfer-encoding", "upgrade", "te", "trailers"
    );

    // Block sensitive cloud metadata and link-local addresses
    private static final Set<String> BLOCKED_HOSTS = Set.of(
        "169.254.169.254", "metadata.google.internal", "instance-data"
    );

    private final Socket clientSocket;
    private final HttpCache cache;

    public ClientHandler(Socket clientSocket, HttpCache cache) {
        this.clientSocket = clientSocket;
        this.cache = cache;
    }

    @Override
    public void run() {
        try (Socket socket = this.clientSocket;
             InputStream in = socket.getInputStream();
             OutputStream out = socket.getOutputStream()) {

            socket.setSoTimeout(SOCKET_TIMEOUT_MS);

            String requestLine = readLine(in);
            if (requestLine == null || requestLine.isBlank()) {
                return;
            }

            Map<String, String> clientHeaders = new LinkedHashMap<>();
            String headerLine;
            while ((headerLine = readLine(in)) != null && !headerLine.isEmpty()) {
                int colonIndex = headerLine.indexOf(':');
                if (colonIndex > 0) {
                    String name = headerLine.substring(0, colonIndex).trim();
                    String value = headerLine.substring(colonIndex + 1).trim();
                    clientHeaders.put(name, value);
                }
            }

            String[] parts = requestLine.split("\\s+");
            if (parts.length < 2) {
                sendErrorResponse(out, "400 Bad Request", "Malformed HTTP request line.");
                return;
            }

            String method = parts[0].toUpperCase();
            String target = parts[1];

            // 1. Handle HTTPS CONNECT Tunneling
            if ("CONNECT".equals(method)) {
                handleConnectTunnel(target, in, out);
                return;
            }

            // 2. Resolve Target URL
            String targetUrl = normalizeUrl(target, clientHeaders);
            if (targetUrl == null) {
                sendErrorResponse(out, "400 Bad Request", "Unable to resolve target URL from request and Host header.");
                return;
            }

            // SSRF Safety Check
            if (isBlockedTarget(targetUrl)) {
                sendErrorResponse(out, "403 Forbidden", "Access to cloud metadata and link-local destinations is blocked.");
                return;
            }

            // 3. Handle GET Requests via In-Memory Cache
            if ("GET".equals(method)) {
                byte[] cachedResponse = cache.get(targetUrl);
                if (cachedResponse != null) {
                    sendResponse(out, cachedResponse, "HIT");
                    return;
                }

                UpstreamFetchResult upstream = fetchUpstreamGet(targetUrl, clientHeaders);
                if (upstream != null) {
                    if (upstream.cacheable) {
                        cache.put(targetUrl, upstream.data, upstream.ttlMillis);
                        sendResponse(out, upstream.data, "MISS");
                    } else {
                        // Forward without caching (e.g., Cache-Control: no-store / private or 4xx/5xx)
                        sendResponse(out, upstream.data, "BYPASS");
                    }
                } else {
                    sendErrorResponse(out, "502 Bad Gateway", "Unable to fetch upstream resource from " + targetUrl);
                }
                return;
            }

            // 4. Handle Pass-through for other HTTP methods (POST, PUT, DELETE, HEAD, OPTIONS)
            handlePassThroughMethod(method, targetUrl, clientHeaders, in, out);

        } catch (SocketTimeoutException ignored) {
        } catch (Exception ignored) {
        }
    }

    private void handleConnectTunnel(String target, InputStream clientIn, OutputStream clientOut) {
        String[] hostPort = target.split(":");
        String host = hostPort[0];
        int port = (hostPort.length > 1) ? Integer.parseInt(hostPort[1]) : 443;

        if (BLOCKED_HOSTS.contains(host.toLowerCase())) {
            sendErrorResponse(clientOut, "403 Forbidden", "Access to cloud metadata destination is prohibited.");
            return;
        }

        try (Socket upstreamSocket = new Socket()) {
            upstreamSocket.connect(new InetSocketAddress(host, port), SOCKET_TIMEOUT_MS);
            upstreamSocket.setSoTimeout(SOCKET_TIMEOUT_MS);

            String response = "HTTP/1.1 200 Connection Established\r\n" +
                              "Proxy-Agent: Java-VirtualThread-Proxy/2.0\r\n\r\n";
            clientOut.write(response.getBytes(StandardCharsets.US_ASCII));
            clientOut.flush();

            InputStream upstreamIn = upstreamSocket.getInputStream();
            OutputStream upstreamOut = upstreamSocket.getOutputStream();

            Thread clientToUpstream = Thread.ofVirtual().name("tunnel-c2u").start(() -> pipeStreams(clientIn, upstreamOut));
            Thread upstreamToClient = Thread.ofVirtual().name("tunnel-u2c").start(() -> pipeStreams(upstreamIn, clientOut));

            clientToUpstream.join();
            upstreamToClient.join();

        } catch (Exception e) {
            sendErrorResponse(clientOut, "502 Bad Gateway", "Failed to establish CONNECT tunnel to " + target);
        }
    }

    private void pipeStreams(InputStream in, OutputStream out) {
        try {
            byte[] buffer = new byte[BUFFER_SIZE];
            int bytesRead;
            while ((bytesRead = in.read(buffer)) != -1) {
                out.write(buffer, 0, bytesRead);
                out.flush();
            }
        } catch (IOException ignored) {
        }
    }

    private String normalizeUrl(String target, Map<String, String> headers) {
        if (target.startsWith("http://") || target.startsWith("https://")) {
            return target;
        }
        if (target.startsWith("/")) {
            String host = headers.get("Host");
            if (host == null) {
                host = headers.get("host");
            }
            if (host != null) {
                return "http://" + host + target;
            }
        }
        return null;
    }

    private boolean isBlockedTarget(String urlString) {
        try {
            URI uri = URI.create(urlString);
            String host = uri.getHost();
            if (host != null && BLOCKED_HOSTS.contains(host.toLowerCase())) {
                return true;
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    private static class UpstreamFetchResult {
        final byte[] data;
        final boolean cacheable;
        final long ttlMillis;

        UpstreamFetchResult(byte[] data, boolean cacheable, long ttlMillis) {
            this.data = data;
            this.cacheable = cacheable;
            this.ttlMillis = ttlMillis;
        }
    }

    private UpstreamFetchResult fetchUpstreamGet(String targetUrl, Map<String, String> clientHeaders) {
        HttpURLConnection connection = null;
        try {
            URL url = URI.create(targetUrl).toURL();
            connection = (HttpURLConnection) url.openConnection(Proxy.NO_PROXY);
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(SOCKET_TIMEOUT_MS);
            connection.setReadTimeout(SOCKET_TIMEOUT_MS);
            connection.setInstanceFollowRedirects(true);

            for (Map.Entry<String, String> entry : clientHeaders.entrySet()) {
                if (!isHopByHopHeader(entry.getKey())) {
                    connection.setRequestProperty(entry.getKey(), entry.getValue());
                }
            }

            if (!clientHeaders.containsKey("User-Agent")) {
                connection.setRequestProperty("User-Agent", "Java-Http-Proxy/2.0 (VirtualThreads)");
            }

            int responseCode = connection.getResponseCode();
            String responseMessage = connection.getResponseMessage();

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            String statusLine = "HTTP/1.1 " + responseCode + " " + (responseMessage != null ? responseMessage : "") + "\r\n";
            baos.write(statusLine.getBytes(StandardCharsets.US_ASCII));

            Map<String, List<String>> headers = connection.getHeaderFields();
            String cacheControl = connection.getHeaderField("Cache-Control");

            for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
                String headerName = entry.getKey();
                if (headerName == null || isHopByHopHeader(headerName)) {
                    continue;
                }
                for (String headerValue : entry.getValue()) {
                    String headerLine = headerName + ": " + headerValue + "\r\n";
                    baos.write(headerLine.getBytes(StandardCharsets.US_ASCII));
                }
            }
            baos.write("\r\n".getBytes(StandardCharsets.US_ASCII));

            InputStream upstreamIn = (responseCode >= 400) ? connection.getErrorStream() : connection.getInputStream();
            if (upstreamIn != null) {
                try (upstreamIn) {
                    byte[] buffer = new byte[BUFFER_SIZE];
                    int bytesRead;
                    while ((bytesRead = upstreamIn.read(buffer)) != -1) {
                        baos.write(buffer, 0, bytesRead);
                    }
                }
            }

            // Determine cacheability (RFC 7234 rules)
            boolean cacheable = (responseCode == 200 || responseCode == 203 || responseCode == 300 || responseCode == 301);
            long ttlMillis = cache.getDefaultTtlMillis();

            if (cacheControl != null) {
                String ccLower = cacheControl.toLowerCase();
                if (ccLower.contains("no-store") || ccLower.contains("no-cache") || ccLower.contains("private")) {
                    cacheable = false;
                }
                Matcher matcher = MAX_AGE_PATTERN.matcher(cacheControl);
                if (matcher.find()) {
                    long maxAgeSec = Long.parseLong(matcher.group(1));
                    if (maxAgeSec > 0) {
                        ttlMillis = maxAgeSec * 1000L;
                    } else {
                        cacheable = false;
                    }
                }
            }

            return new UpstreamFetchResult(baos.toByteArray(), cacheable, ttlMillis);
        } catch (Exception e) {
            return null;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private void handlePassThroughMethod(String method, String targetUrl, Map<String, String> headers,
                                        InputStream clientIn, OutputStream clientOut) {
        HttpURLConnection connection = null;
        try {
            URL url = URI.create(targetUrl).toURL();
            connection = (HttpURLConnection) url.openConnection(Proxy.NO_PROXY);
            connection.setRequestMethod(method);
            connection.setConnectTimeout(SOCKET_TIMEOUT_MS);
            connection.setReadTimeout(SOCKET_TIMEOUT_MS);

            for (Map.Entry<String, String> entry : headers.entrySet()) {
                if (!isHopByHopHeader(entry.getKey())) {
                    connection.setRequestProperty(entry.getKey(), entry.getValue());
                }
            }

            if (headers.containsKey("Content-Length")) {
                int contentLength = Integer.parseInt(headers.get("Content-Length"));
                if (contentLength > 0) {
                    connection.setDoOutput(true);
                    try (OutputStream upstreamOut = connection.getOutputStream()) {
                        byte[] buffer = new byte[BUFFER_SIZE];
                        int remaining = contentLength;
                        while (remaining > 0) {
                            int toRead = Math.min(buffer.length, remaining);
                            int read = clientIn.read(buffer, 0, toRead);
                            if (read == -1) break;
                            upstreamOut.write(buffer, 0, read);
                            remaining -= read;
                        }
                        upstreamOut.flush();
                    }
                }
            }

            int responseCode = connection.getResponseCode();
            String responseMessage = connection.getResponseMessage();

            String statusLine = "HTTP/1.1 " + responseCode + " " + (responseMessage != null ? responseMessage : "") + "\r\n";
            clientOut.write(statusLine.getBytes(StandardCharsets.US_ASCII));

            for (Map.Entry<String, List<String>> entry : connection.getHeaderFields().entrySet()) {
                String headerName = entry.getKey();
                if (headerName == null || isHopByHopHeader(headerName)) continue;
                for (String headerValue : entry.getValue()) {
                    clientOut.write((headerName + ": " + headerValue + "\r\n").getBytes(StandardCharsets.US_ASCII));
                }
            }
            clientOut.write("\r\n".getBytes(StandardCharsets.US_ASCII));

            InputStream upstreamIn = (responseCode >= 400) ? connection.getErrorStream() : connection.getInputStream();
            if (upstreamIn != null) {
                try (upstreamIn) {
                    pipeStreams(upstreamIn, clientOut);
                }
            }
            clientOut.flush();
        } catch (Exception e) {
            sendErrorResponse(clientOut, "502 Bad Gateway", "Error relaying " + method + " to upstream: " + e.getMessage());
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private void sendResponse(OutputStream out, byte[] responseData, String cacheStatus) throws Exception {
        int headerEnd = -1;
        for (int i = 0; i < responseData.length - 3; i++) {
            if (responseData[i] == '\r' && responseData[i + 1] == '\n' &&
                responseData[i + 2] == '\r' && responseData[i + 3] == '\n') {
                headerEnd = i;
                break;
            }
        }

        if (headerEnd != -1) {
            out.write(responseData, 0, headerEnd);
            out.write(("\r\nX-Cache: " + cacheStatus + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            int bodyStart = headerEnd + 4;
            if (bodyStart < responseData.length) {
                out.write(responseData, bodyStart, responseData.length - bodyStart);
            }
        } else {
            out.write(responseData);
        }
        out.flush();
    }

    private boolean isHopByHopHeader(String headerName) {
        return HOP_BY_HOP_HEADERS.contains(headerName.toLowerCase());
    }

    private void sendErrorResponse(OutputStream out, String status, String message) {
        try {
            String body = "<html><body><h1>" + status + "</h1><p>" + message + "</p></body></html>";
            byte[] bodyBytes = body.getBytes(StandardCharsets.UTF_8);
            String response = "HTTP/1.1 " + status + "\r\n" +
                              "Content-Type: text/html; charset=UTF-8\r\n" +
                              "Content-Length: " + bodyBytes.length + "\r\n" +
                              "Connection: close\r\n\r\n";
            out.write(response.getBytes(StandardCharsets.US_ASCII));
            out.write(bodyBytes);
            out.flush();
        } catch (Exception ignored) {
        }
    }

    private String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        int b;
        while ((b = in.read()) != -1) {
            if (b == '\n') {
                break;
            }
            if (b != '\r') {
                buffer.write(b);
            }
        }
        if (b == -1 && buffer.size() == 0) {
            return null;
        }
        return buffer.toString(StandardCharsets.ISO_8859_1);
    }
}
