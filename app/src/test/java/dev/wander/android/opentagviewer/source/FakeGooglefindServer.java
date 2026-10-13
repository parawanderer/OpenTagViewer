package dev.wander.android.opentagviewer.source;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A googlefind service on 127.0.0.1, for {@code ExtraSourcesApiTest}: answers each path with a
 * canned status and body, and records what was asked.
 *
 * <p>A plain {@link ServerSocket} rather than the JDK's HTTP server, which is not on the unit test
 * compile classpath here - the same choice as {@code FakeApkServer}.
 */
final class FakeGooglefindServer implements Closeable {

    /** One request as it arrived. Header names are lowercased. */
    static final class Request {
        final String method;
        final String target;
        final Map<String, String> headers;
        final String body;

        Request(final String method, final String target, final Map<String, String> headers, final String body) {
            this.method = method;
            this.target = target;
            this.headers = headers;
            this.body = body;
        }
    }

    private static final class Answer {
        final int status;
        final String body;

        Answer(final int status, final String body) {
            this.status = status;
            this.body = body;
        }
    }

    private final ServerSocket socket;
    private final AtomicBoolean stopped = new AtomicBoolean(false);
    private final Map<String, Answer> answers = new HashMap<>();
    private final List<Request> requests = new ArrayList<>();

    FakeGooglefindServer() throws IOException {
        // Port 0: the OS picks a free one, so parallel tests cannot collide on a fixed number.
        this.socket = new ServerSocket(0, 0, InetAddress.getByName("127.0.0.1"));
        final Thread thread = new Thread(this::serveUntilStopped, "FakeGooglefindServer");
        thread.setDaemon(true);
        thread.start();
    }

    String url() {
        return "http://127.0.0.1:" + this.socket.getLocalPort();
    }

    /** Answer any request whose path (query excluded) is {@code path}. Unknown paths get 404. */
    synchronized FakeGooglefindServer answer(final String path, final int status, final String body) {
        this.answers.put(path, new Answer(status, body));
        return this;
    }

    synchronized List<Request> requests() {
        return new ArrayList<>(this.requests);
    }

    synchronized Request lastRequest() {
        return this.requests.get(this.requests.size() - 1);
    }

    @Override
    public void close() throws IOException {
        this.stopped.set(true);
        this.socket.close();
    }

    private void serveUntilStopped() {
        while (!this.stopped.get()) {
            try (Socket client = this.socket.accept()) {
                this.handle(client);
            } catch (final IOException e) {
                if (!this.stopped.get()) {
                    // Printed: on its own thread an exception otherwise surfaces only as a test
                    // timing out on a read, with nothing to say why.
                    System.err.println("FakeGooglefindServer: failed to answer a request: " + e);
                }
                return;
            }
        }
    }

    private void handle(final Socket client) throws IOException {
        final InputStream in = client.getInputStream();
        final String[] head = readHead(in).split("\r\n");
        final String[] requestLine = head[0].split(" ");
        final Map<String, String> headers = new HashMap<>();
        for (int i = 1; i < head.length; i++) {
            final int colon = head[i].indexOf(':');
            if (colon > 0) {
                headers.put(head[i].substring(0, colon).trim().toLowerCase(Locale.ROOT),
                        head[i].substring(colon + 1).trim());
            }
        }
        final int length = headers.containsKey("content-length")
                ? Integer.parseInt(headers.get("content-length")) : 0;
        final String body = new String(in.readNBytes(length), StandardCharsets.UTF_8);

        final String target = requestLine[1];
        final String path = target.contains("?") ? target.substring(0, target.indexOf('?')) : target;
        final Answer answer;
        synchronized (this) {
            this.requests.add(new Request(requestLine[0], target, headers, body));
            answer = this.answers.getOrDefault(path, new Answer(404, "{\"detail\":\"Not Found\"}"));
        }

        final byte[] payload = answer.body.getBytes(StandardCharsets.UTF_8);
        final OutputStream out = client.getOutputStream();
        out.write(("HTTP/1.1 " + answer.status + " X\r\n"
                + "Content-Type: application/json\r\n"
                + "Content-Length: " + payload.length + "\r\n"
                + "Connection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(payload);
        out.flush();
    }

    private static String readHead(final InputStream in) throws IOException {
        final ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int matched = 0;
        final byte[] end = {'\r', '\n', '\r', '\n'};
        int b;
        while (matched < 4 && (b = in.read()) != -1) {
            buf.write(b);
            matched = b == end[matched] ? matched + 1 : (b == '\r' ? 1 : 0);
        }
        final String head = buf.toString(StandardCharsets.UTF_8.name());
        return head.substring(0, Math.max(0, head.length() - 4));
    }
}
