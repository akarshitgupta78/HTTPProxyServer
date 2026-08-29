import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.URL;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ClientHandler implements Runnable {

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

            socket.setSoTimeout(5000);

            BufferedReader reader = new BufferedReader(new InputStreamReader(in));
            String requestLine = reader.readLine();

            if (requestLine == null || requestLine.isBlank()) {
                return;
            }

            // Read client HTTP headers
            Map<String, String> clientHeaders = new LinkedHashMap<>();
            String header;
            while ((header = reader.readLine()) != null && !header.isEmpty()) {
                int colonIndex = header.indexOf(':');
                if (colonIndex > 0) {
                    String headerName = header.substring(0, colonIndex).trim();
                    String headerValue = header.substring(colonIndex + 1).trim();
                    clientHeaders.put(headerName, headerValue);
                }
            }

            String[] parts = requestLine.split("\\s+");
            if (parts.length < 2) {
                sendErrorResponse(out, "400 Bad Request", "Malformed request line.");
                return;
            }

            String method = parts[0];
            String target = parts[1];

            if (!"GET".equalsIgnoreCase(method)) {
                sendErrorResponse(out, "400 Bad Request", "Only HTTP GET method is supported.");
                return;
            }

            String targetUrl = normalizeUrl(target);
            if (targetUrl == null) {
                sendErrorResponse(out, "400 Bad Request", "Invalid target URL.");
                return;
            }

            // Check cache
            byte[] cachedResponse = cache.get(targetUrl);
            if (cachedResponse != null) {
                sendResponse(out, cachedResponse, "HIT");
                return;
            }

            // Upstream fetch on cache miss
            byte[] upstreamResponse = fetchUpstreamResponse(targetUrl, clientHeaders);
            if (upstreamResponse != null) {
                cache.put(targetUrl, upstreamResponse);
                sendResponse(out, upstreamResponse, "MISS");
            } else {
                sendErrorResponse(out, "502 Bad Gateway", "Unable to fetch upstream resource.");
            }

        } catch (SocketTimeoutException ignored) {
        } catch (Exception e) {
        }
    }

    private String normalizeUrl(String target) {
        if (target.startsWith("http://") || target.startsWith("https://")) {
            return target;
        }
        if (target.startsWith("/")) {
            return "http://" + target;
        }
        return null;
    }

    private byte[] fetchUpstreamResponse(String targetUrl, Map<String, String> clientHeaders) {
        HttpURLConnection connection = null;
        try {
            URL url = URI.create(targetUrl).toURL();
            connection = (HttpURLConnection) url.openConnection(Proxy.NO_PROXY);
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(5000);
            connection.setInstanceFollowRedirects(true);

            // Forward client request headers (excluding hop-by-hop headers)
            for (Map.Entry<String, String> entry : clientHeaders.entrySet()) {
                String name = entry.getKey();
                if (isHopByHopHeader(name)) {
                    continue;
                }
                connection.setRequestProperty(name, entry.getValue());
            }

            if (!clientHeaders.containsKey("User-Agent")) {
                connection.setRequestProperty("User-Agent", "Java-Http-Proxy/2.0");
            }

            int responseCode = connection.getResponseCode();
            String responseMessage = connection.getResponseMessage();

            ByteArrayOutputStream baos = new ByteArrayOutputStream();

            String statusLine = "HTTP/1.1 " + responseCode + " " + (responseMessage != null ? responseMessage : "") + "\r\n";
            baos.write(statusLine.getBytes());

            Map<String, List<String>> headers = connection.getHeaderFields();
            for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
                String headerName = entry.getKey();
                if (headerName == null || headerName.equalsIgnoreCase("Transfer-Encoding")) {
                    continue;
                }
                for (String headerValue : entry.getValue()) {
                    String headerLine = headerName + ": " + headerValue + "\r\n";
                    baos.write(headerLine.getBytes());
                }
            }
            baos.write("\r\n".getBytes());

            InputStream upstreamIn = (responseCode >= 400) ? connection.getErrorStream() : connection.getInputStream();
            if (upstreamIn != null) {
                byte[] buffer = new byte[8192];
                int bytesRead;
                while ((bytesRead = upstreamIn.read(buffer)) != -1) {
                    baos.write(buffer, 0, bytesRead);
                }
                upstreamIn.close();
            }

            return baos.toByteArray();
        } catch (Exception e) {
            return null;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
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
            out.write(("\r\nX-Cache: " + cacheStatus + "\r\n\r\n").getBytes());
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
        return headerName.equalsIgnoreCase("Host") ||
               headerName.equalsIgnoreCase("Connection") ||
               headerName.equalsIgnoreCase("Proxy-Connection") ||
               headerName.equalsIgnoreCase("Keep-Alive") ||
               headerName.equalsIgnoreCase("Transfer-Encoding") ||
               headerName.equalsIgnoreCase("Upgrade");
    }

    private void sendErrorResponse(OutputStream out, String status, String message) {
        try {
            String body = "<html><body><h1>" + status + "</h1><p>" + message + "</p></body></html>";
            String response = "HTTP/1.1 " + status + "\r\n" +
                              "Content-Type: text/html\r\n" +
                              "Content-Length: " + body.getBytes().length + "\r\n" +
                              "Connection: close\r\n\r\n" +
                              body;
            out.write(response.getBytes());
            out.flush();
        } catch (Exception ignored) {
        }
    }
}
