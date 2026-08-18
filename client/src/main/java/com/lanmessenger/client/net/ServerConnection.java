package com.lanmessenger.client.net;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Owns a single client-side TCP connection to the chat server and its UTF-8
 * line-oriented text streams.
 *
 * <p>This is the client mirror of the server's per-connection plumbing: it wraps
 * one {@link Socket} in a {@link BufferedReader} (for {@link BufferedReader#readLine()})
 * and an auto-flushing {@link PrintWriter}, using {@link StandardCharsets#UTF_8}
 * on both ends so the two sides always agree on the encoding. It deliberately
 * knows nothing about the protocol, threading, or the UI &mdash; it is purely the
 * transport, which keeps it trivial to reason about and to close cleanly.
 *
 * <p>Instances are created already-connected through {@link #open(String, int, int)}
 * so a {@code ServerConnection} always represents a live (or since-closed) socket,
 * never a half-initialised one.
 */
public final class ServerConnection implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(ServerConnection.class.getName());

    private final Socket socket;
    private final BufferedReader reader;
    private final PrintWriter writer;

    private ServerConnection(Socket socket) throws IOException {
        this.socket = socket;
        this.reader = new BufferedReader(
                new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
        this.writer = new PrintWriter(
                new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true);
    }

    /**
     * Opens a TCP connection to {@code host:port} and wraps it, applying a bounded
     * connect timeout so an unreachable address fails fast instead of hanging.
     *
     * @param host                 the server host name or IP address
     * @param port                 the server TCP port ({@code 1..65535})
     * @param connectTimeoutMillis how long to wait for the TCP handshake, in
     *                             milliseconds ({@code 0} means "block indefinitely")
     * @return a live connection with its streams ready
     * @throws IOException              if the connection cannot be established
     * @throws IllegalArgumentException if the arguments are obviously invalid
     */
    public static ServerConnection open(String host, int port, int connectTimeoutMillis)
            throws IOException {
        Objects.requireNonNull(host, "host");
        if (host.isBlank()) {
            throw new IllegalArgumentException("host must not be blank");
        }
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("port out of range (1-65535): " + port);
        }
        if (connectTimeoutMillis < 0) {
            throw new IllegalArgumentException("connect timeout must not be negative");
        }

        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress(host, port), connectTimeoutMillis);
            tune(socket);
            return new ServerConnection(socket);
        } catch (IOException | RuntimeException ex) {
            closeQuietly(socket);
            throw ex;
        }
    }

    /**
     * Applies connection-health socket options: {@code TCP_NODELAY} so small chat
     * lines are sent promptly (no Nagle coalescing) and {@code SO_KEEPALIVE} so the
     * OS can eventually detect a server that vanished without a clean close. Tuning
     * is best-effort; a failure here does not prevent using the connection.
     */
    private static void tune(Socket socket) {
        try {
            socket.setTcpNoDelay(true);
            socket.setKeepAlive(true);
        } catch (SocketException ex) {
            LOG.log(Level.FINE, ex, () -> "Could not tune socket options");
        }
    }

    /** @return the reader for consuming server messages line by line. */
    BufferedReader reader() {
        return reader;
    }

    /** @return the auto-flushing writer for sending encoded messages. */
    PrintWriter writer() {
        return writer;
    }

    /** @return a description of the remote endpoint, for logging. */
    public String remoteAddress() {
        return String.valueOf(socket.getRemoteSocketAddress());
    }

    /** @return {@code true} once the underlying socket has been closed. */
    public boolean isClosed() {
        return socket.isClosed();
    }

    /**
     * Closes the socket (and, transitively, its streams). Idempotent and quiet:
     * closing an already-closed connection does nothing, and any error while
     * closing is swallowed because there is nothing useful to do about it. Closing
     * also unblocks a thread parked in {@link BufferedReader#readLine()}.
     */
    @Override
    public void close() {
        closeQuietly(socket);
    }

    private static void closeQuietly(Socket socket) {
        try {
            socket.close();
        } catch (IOException ignored) {
            // Already closed or never fully opened — nothing useful to do.
        }
    }
}
