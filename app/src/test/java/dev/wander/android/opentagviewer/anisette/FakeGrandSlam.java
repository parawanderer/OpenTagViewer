package dev.wander.android.opentagviewer.anisette;

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
 * A stand-in for Apple's provisioning endpoints: answers by path, and keeps every request.
 *
 * <p>The same idea as {@link FakeApkServer}, for the other conversation: a {@code ServerSocket} on
 * {@code 127.0.0.1} and a few lines of HTTP, so the requests {@link MacProvisioning} sends can be
 * read back exactly as they went on the wire (AGENTS.md rule 13). One request per connection,
 * answered with {@code Connection: close}.
 */
final class FakeGrandSlam implements Closeable {

    /** One request as it arrived. Header names are lower-cased. */
    static final class Request {
        final String method;
        final String path;
        final Map<String, String> headers;
        final String body;

        Request(final String method, final String path, final Map<String, String> headers,
                final String body) {
            this.method = method;
            this.path = path;
            this.headers = headers;
            this.body = body;
        }
    }

    /** What to answer on one path. */
    static final class Answer {
        final int status;
        final String body;

        Answer(final int status, final String body) {
            this.status = status;
            this.body = body;
        }
    }

    private final ServerSocket socket;
    private final Map<String, Answer> answers = new HashMap<>();
    private final List<Request> requests = new ArrayList<>();
    private final AtomicBoolean stopped = new AtomicBoolean(false);

    FakeGrandSlam() throws IOException {
        this.socket = new ServerSocket(0, 0, InetAddress.getByName("127.0.0.1"));
        final Thread thread = new Thread(this::serve, "FakeGrandSlam");
        thread.setDaemon(true);
        thread.start();
    }

    String url(final String path) {
        return "http://127.0.0.1:" + this.socket.getLocalPort() + path;
    }

    synchronized FakeGrandSlam answer(final String path, final int status, final String body) {
        this.answers.put(path, new Answer(status, body));
        return this;
    }

    synchronized List<Request> requests() {
        return new ArrayList<>(this.requests);
    }

    private void serve() {
        while (!this.stopped.get()) {
            try (Socket client = this.socket.accept()) {
                handle(client);
            } catch (final IOException e) {
                if (this.stopped.get()) {
                    return;
                }
            }
        }
    }

    private void handle(final Socket client) throws IOException {
        final InputStream in = client.getInputStream();
        final String[] start = readLine(in).split(" ");
        final Map<String, String> headers = new HashMap<>();
        String line;
        while (!(line = readLine(in)).isEmpty()) {
            final int colon = line.indexOf(':');
            headers.put(line.substring(0, colon).trim().toLowerCase(Locale.ROOT),
                    line.substring(colon + 1).trim());
        }
        final int length = Integer.parseInt(headers.getOrDefault("content-length", "0"));
        final byte[] body = in.readNBytes(length);

        final Answer answer;
        synchronized (this) {
            this.requests.add(new Request(start[0], start[1], headers,
                    new String(body, StandardCharsets.UTF_8)));
            answer = this.answers.getOrDefault(start[1], new Answer(404, "not here"));
        }

        final byte[] payload = answer.body.getBytes(StandardCharsets.UTF_8);
        final OutputStream out = client.getOutputStream();
        out.write(("HTTP/1.1 " + answer.status + " X\r\n"
                + "Content-Type: text/xml\r\n"
                + "Content-Length: " + payload.length + "\r\n"
                + "Connection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        out.write(payload);
        out.flush();
    }

    private static String readLine(final InputStream in) throws IOException {
        final ByteArrayOutputStream line = new ByteArrayOutputStream();
        int b;
        while ((b = in.read()) != -1 && b != '\n') {
            if (b != '\r') {
                line.write(b);
            }
        }
        return line.toString(StandardCharsets.US_ASCII.name());
    }

    @Override
    public void close() throws IOException {
        this.stopped.set(true);
        this.socket.close();
    }
}
