import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class ProxyServer implements Runnable {

    private static final int DEFAULT_PORT = 8000;
    private static final int THREAD_POOL_SIZE = 16;

    private final int port; 
    private final HttpCache cache;
    private final ExecutorService threadPool;
    private ServerSocket serverSocket;
    private volatile boolean running = true;

    public ProxyServer(int port, HttpCache cache) {
        this.port = port;
        this.cache = cache;
        this.threadPool = Executors.newVirtualThreadPerTaskExecutor();
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
                System.err.println("Invalid port provided, using default: " + DEFAULT_PORT);
            }
        }

        ProxyServer server = new ProxyServer(port);
        Runtime.getRuntime().addShutdownHook(new Thread(server::stop, "ProxyServer-ShutdownHook"));

        System.out.println("Starting Caching HTTP Proxy on port " + port + "...");
        server.run();
    }

    @Override
    public void run() {
        try {
            serverSocket = new ServerSocket(port);
            System.out.println("ProxyServer listening on http://localhost:" + port);

            while (running) {
                try {
                    Socket clientSocket = serverSocket.accept();
                    threadPool.submit(new ClientHandler(clientSocket, cache));
                } catch (SocketException se) {
                    if (!running) {
                        System.out.println("ProxyServer socket closed.");
                        break;
                    }
                    System.err.println("Socket exception: " + se.getMessage());
                } catch (IOException e) {
                    if (running) {
                        System.err.println("Error accepting connection: " + e.getMessage());
                    }
                }
            }
        } catch (IOException e) {
            System.err.println("Could not bind ServerSocket on port " + port + ": " + e.getMessage());
        } finally {
            stop();
        }
    }

    public void stop() {
        if (!running) {
            return;
        }
        running = false;
        System.out.println("Shutting down ProxyServer...");

        if (serverSocket != null && !serverSocket.isClosed()) {
            try {
                serverSocket.close();
            } catch (IOException ignored) {
            }
        }

        threadPool.shutdown();
        try {
            if (!threadPool.awaitTermination(5, TimeUnit.SECONDS)) {
                threadPool.shutdownNow();
            }
        } catch (InterruptedException e) {
            threadPool.shutdownNow();
            Thread.currentThread().interrupt();
        }

        System.out.println("ProxyServer stopped.");
    }

    public HttpCache getCache() {
        return cache;
    }
}
