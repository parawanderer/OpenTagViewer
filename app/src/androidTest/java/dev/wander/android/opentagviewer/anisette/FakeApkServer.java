package dev.wander.android.opentagviewer.anisette;

import android.util.Log;

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
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A stand-in for Apple's CDN: serves one byte array over HTTP, and honours range requests.
 *
 * <p><b>Written by hand rather than pulled in.</b> This needs about forty lines of HTTP - HEAD
 * for a length, GET with a {@code Range} for a slice - and the alternative is a test-only
 * dependency for a project that has none. It is a fake of one server's two behaviours, not an
 * HTTP implementation, and it should stay that way.
 *
 * <p><b>It can be told to misbehave</b>, which is most of why it exists. Apple's CDN honouring
 * range requests is an assumption the whole design rests on: without ranges the app would have
 * to pull 142 MB onto somebody's phone. {@link #ignoringRanges()} produces the server that
 * quietly sends the whole file with a 200 instead, which is what a CDN change or an intercepting
 * proxy looks like, and the app must notice rather than start parsing a zip from the wrong
 * offset.
 */
public final class FakeApkServer implements Closeable {

    private static final String TAG = "FakeApkServer";

    private final ServerSocket socket;
    private final byte[] body;
    private final boolean honourRanges;
    private final AtomicBoolean stopped = new AtomicBoolean(false);
    private final Thread thread;

    /** Every range asked for, in order, as "start-end". Read after a fetch to see what it did. */
    private final List<String> rangesRequested = new ArrayList<>();

    private FakeApkServer(final byte[] body, final boolean honourRanges) throws IOException {
        this.body = body;
        this.honourRanges = honourRanges;
        // Port 0: the OS picks a free one, so parallel tests cannot collide on a fixed number.
        this.socket = new ServerSocket(0, 0, InetAddress.getByName("127.0.0.1"));

        this.thread = new Thread(this::serveUntilStopped, "FakeApkServer");
        this.thread.setDaemon(true);
        this.thread.start();
    }

    public static FakeApkServer serving(final byte[] body) throws IOException {
        return new FakeApkServer(body, true);
    }

    /** A server that answers every request with the whole file and a 200, ranges ignored. */
    public static FakeApkServer ignoringRanges(final byte[] body) throws IOException {
        return new FakeApkServer(body, false);
    }

    public String url() {
        return "http://127.0.0.1:" + this.socket.getLocalPort() + "/applemusic.apk";
    }

    public synchronized List<String> rangesRequested() {
        return new ArrayList<>(this.rangesRequested);
    }

    /** How many bytes this server actually handed over - the claim "we do not pull 142 MB". */
    public synchronized long bytesServed() {
        long total = 0;
        for (final String range : this.rangesRequested) {
            final String[] parts = range.split("-");
            total += Long.parseLong(parts[1]) - Long.parseLong(parts[0]) + 1;
        }
        return total;
    }

    @Override
    public void close() throws IOException {
        this.stopped.set(true);
        this.socket.close();
    }

    private void serveUntilStopped() {
        while (!this.stopped.get()) {
            try (Socket client = this.socket.accept()) {
                this.answer(client);
            } catch (final IOException e) {
                if (!this.stopped.get()) {
                    Log.w(TAG, "failed to answer a request", e);
                }
                return;
            }
        }
    }

    private void answer(final Socket client) throws IOException {
        final String request = readRequestHead(client.getInputStream());
        final OutputStream out = client.getOutputStream();

        if (request.startsWith("HEAD")) {
            // Content-Length on a HEAD describes the file, and there is no body.
            out.write(header(200, "OK", this.body.length, null).getBytes(StandardCharsets.UTF_8));
            out.flush();
            return;
        }

        final String range = headerValue(request, "Range");
        if (range == null || !this.honourRanges) {
            out.write(header(200, "OK", this.body.length, null).getBytes(StandardCharsets.UTF_8));
            out.write(this.body);
            out.flush();
            return;
        }

        // "bytes=start-end", inclusive at both ends, which is what the fetcher sends.
        final String[] bounds = range.substring(range.indexOf('=') + 1).split("-");
        final int start = Integer.parseInt(bounds[0].trim());
        final int end = Math.min(Integer.parseInt(bounds[1].trim()), this.body.length - 1);

        synchronized (this) {
            this.rangesRequested.add(start + "-" + end);
        }

        final int length = end - start + 1;
        final String contentRange = "bytes " + start + "-" + end + "/" + this.body.length;

        out.write(header(206, "Partial Content", length, contentRange)
                .getBytes(StandardCharsets.UTF_8));
        out.write(this.body, start, length);
        out.flush();
    }

    private static String header(
            final int code, final String reason, final int length, final String contentRange) {
        final StringBuilder head = new StringBuilder()
                .append("HTTP/1.1 ").append(code).append(' ').append(reason).append("\r\n")
                .append("Content-Length: ").append(length).append("\r\n")
                .append("Accept-Ranges: bytes\r\n")
                .append("Connection: close\r\n");

        if (contentRange != null) {
            head.append("Content-Range: ").append(contentRange).append("\r\n");
        }
        return head.append("\r\n").toString();
    }

    /**
     * Everything up to the blank line, read a byte at a time.
     *
     * <p>Buffering would be faster and wrong: a reader with a buffer can swallow part of a body
     * that is not ours to consume, and these requests are a few hundred bytes each.
     */
    private static String readRequestHead(final InputStream in) throws IOException {
        final ByteArrayOutputStream head = new ByteArrayOutputStream();
        int last = 0;
        int current;

        while ((current = in.read()) != -1) {
            head.write(current);
            final byte[] so_far = head.toByteArray();
            if (so_far.length >= 4
                    && so_far[so_far.length - 4] == '\r' && so_far[so_far.length - 3] == '\n'
                    && so_far[so_far.length - 2] == '\r' && so_far[so_far.length - 1] == '\n') {
                break;
            }
            last = current;
        }

        return head.toString("UTF-8");
    }

    private static String headerValue(final String request, final String name) {
        for (final String line : request.split("\r\n")) {
            if (line.regionMatches(true, 0, name + ":", 0, name.length() + 1)) {
                return line.substring(name.length() + 1).trim();
            }
        }
        return null;
    }
}
