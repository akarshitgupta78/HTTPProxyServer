package com.proxy.core;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * High-Throughput HTTP Proxy Server using Java 21 Virtual Threads.
 */
public class ProxyServer implements Runnable {

    private static final int DEFAULT_PORT = 8000;

    private final int port;
    private final HttpCache cache;
    private final ExecutorService executorService;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicLong activeConnections = new AtomicLong(0);
    private final AtomicLong totalAcceptedConnections = new AtomicLong(0);
    private final long startTimeMillis;

    private ServerSocket serverSocket;

    public ProxyServer(int port, HttpCache cache) {
        this.port = port;
        this.cache = cache;
        this.executorService = Executors.newVirtualThreadPerTaskExecutor();
        this.startTimeMillis = System.currentTimeMillis();
    }

    public ProxyServer(int port) {
        this(port, new HttpCache());
    }

    public ProxyServer() {
        this(DEFAULT_PORT);
    }

    public static void main(String[] args) {
        int port = DEFAULT_PORT;
        if (args.length > 0) {
            try {
                port = Integer.parseInt(args[0]);
            } catch (NumberFormatException e) {
                System.err.println("Invalid port provided, falling back to default: " + DEFAULT_PORT);
            }
        }

        ProxyServer server = new ProxyServer(port);
        Runtime.getRuntime().addShutdownHook(new Thread(server::stop, "ProxyServer-ShutdownHook"));
        System.out.println("Starting Standalone Caching HTTP Proxy on port " + port + "...");
        server.run();
    }

    @Override
    public void run() {
        if (!running.compareAndSet(false, true)) {
            return;
        }

        try {
            serverSocket = new ServerSocket(port, 1024);
            System.out.println("[ProxyServer] Listening on http://0.0.0.0:" + port + " (Virtual Threads active)");

            while (running.get()) {
                try {
                    Socket clientSocket = serverSocket.accept();
                    totalAcceptedConnections.incrementAndGet();
                    activeConnections.incrementAndGet();

                    executorService.submit(() -> {
                        try {
                            new ClientHandler(clientSocket, cache).run();
                        } finally {
                            activeConnections.decrementAndGet();
                        }
                    });
                } catch (SocketException se) {
                    if (!running.get()) {
                        System.out.println("[ProxyServer] ServerSocket closed gracefully.");
                        break;
                    }
                } catch (IOException e) {
                    if (running.get()) {
                        System.err.println("[ProxyServer] Accept error: " + e.getMessage());
                    }
                }
            }
        } catch (IOException e) {
            System.err.println("[ProxyServer] Could not bind on port " + port + ": " + e.getMessage());
        } finally {
            stop();
        }
    }

    public void stop() {
        if (!running.compareAndSet(true, false)) {
            return;
        }

        System.out.println("[ProxyServer] Stopping proxy server on port " + port + "...");
        if (serverSocket != null && !serverSocket.isClosed()) {
            try {
                serverSocket.close();
            } catch (IOException ignored) {
            }
        }

        executorService.shutdown();
        try {
            if (!executorService.awaitTermination(5, TimeUnit.SECONDS)) {
                executorService.shutdownNow();
            }
        } catch (InterruptedException e) {
            executorService.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    public int getPort() {
        return port;
    }

    public HttpCache getCache() {
        return cache;
    }

    public boolean isRunning() {
        return running.get();
    }

    public long getActiveConnections() {
        return activeConnections.get();
    }

    public long getTotalAcceptedConnections() {
        return totalAcceptedConnections.get();
    }

    public long getUptimeSeconds() {
        return (System.currentTimeMillis() - startTimeMillis) / 1000L;
    }
}
