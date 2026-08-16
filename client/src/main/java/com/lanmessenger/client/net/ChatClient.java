package com.lanmessenger.client.net;

import com.lanmessenger.common.Message;
import com.lanmessenger.common.MessageType;

import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * High-level, UI-agnostic facade for talking to the chat server. This is the one
 * class the rest of the application uses; it hides the socket, streams, threads
 * and protocol handshake behind a small set of intention-revealing methods.
 *
 * <h2>Collaborators</h2>
 * <ul>
 *   <li>{@link ServerConnection} &mdash; owns the socket and its UTF-8 streams;</li>
 *   <li>{@link MessageSender} &mdash; serialises outbound messages;</li>
 *   <li>{@link MessageReader} &mdash; the background read loop for inbound messages;</li>
 *   <li>{@link ChatClientListener} &mdash; where inbound messages and lifecycle
 *       events are reported.</li>
 * </ul>
 *
 * <h2>Threading model</h2>
 * <p>Two dedicated single-thread executors keep all blocking socket I/O off the
 * caller's thread &mdash; and therefore off the JavaFX Application Thread:
 * <ul>
 *   <li>a <b>reader</b> thread runs the {@link MessageReader} loop, so incoming
 *       messages are delivered to the listener off the FX thread;</li>
 *   <li>a <b>sender</b> thread performs every write, so a slow or stalled socket
 *       can never block the UI while sending.</li>
 * </ul>
 * The listener is expected to be a
 * {@link com.lanmessenger.client.FxChatClientListener} when a UI is attached, so
 * those off-thread callbacks are safely re-dispatched onto the FX thread. This
 * class itself never references JavaFX.
 *
 * <p>{@link #connect(String, int, String)} performs the (blocking) TCP handshake
 * on the calling thread and throws on failure, so callers get a direct result;
 * from a JavaFX UI it should be invoked from a background
 * {@link javafx.concurrent.Task}. Everything after connect is asynchronous.
 *
 * <h2>Lifecycle</h2>
 * <p>Teardown is funnelled through a single idempotent path guarded by an
 * {@link AtomicBoolean}, so the listener's {@link ChatClientListener#onDisconnected(String)}
 * fires exactly once whether the disconnect is requested locally, initiated by
 * the server, or caused by an I/O error.
 */
public final class ChatClient {

    private static final Logger LOG = Logger.getLogger(ChatClient.class.getName());

    /** Default connect timeout: fail fast on an unreachable host rather than hang. */
    public static final int DEFAULT_CONNECT_TIMEOUT_MILLIS = 8_000;

    private final ChatClientListener listener;
    private final int connectTimeoutMillis;

    /** {@code true} between a successful {@link #connect} and teardown. */
    private final AtomicBoolean connected = new AtomicBoolean(false);
    /** Ensures teardown/notification runs exactly once. */
    private final AtomicBoolean closing = new AtomicBoolean(false);

    // Live only while connected; all reset on teardown.
    private ServerConnection connection;
    private MessageSender sender;
    private MessageReader reader;
    private ExecutorService readerExecutor;
    private ExecutorService senderExecutor;
    private volatile String username;

    /**
     * Creates a client using the {@linkplain #DEFAULT_CONNECT_TIMEOUT_MILLIS
     * default connect timeout}.
     *
     * @param listener where messages and lifecycle events are reported
     */
    public ChatClient(ChatClientListener listener) {
        this(listener, DEFAULT_CONNECT_TIMEOUT_MILLIS);
    }

    /**
     * @param listener             where messages and lifecycle events are reported
     * @param connectTimeoutMillis TCP connect timeout in milliseconds
     */
    public ChatClient(ChatClientListener listener, int connectTimeoutMillis) {
        this.listener = Objects.requireNonNull(listener, "listener");
        if (connectTimeoutMillis < 0) {
            throw new IllegalArgumentException("connect timeout must not be negative");
        }
        this.connectTimeoutMillis = connectTimeoutMillis;
    }

    // ---------------------------------------------------------------------
    // Connection lifecycle
    // ---------------------------------------------------------------------

    /**
     * Connects to the server, starts the background reader, and sends the initial
     * {@code LOGIN} request for {@code username}.
     *
     * <p>This method blocks for the duration of the TCP handshake and should be
     * called off the JavaFX Application Thread. On success it notifies
     * {@link ChatClientListener#onConnected()}; the server's login verdict arrives
     * later as a {@link MessageType#LOGIN_SUCCESS}/{@link MessageType#LOGIN_FAILED}
     * message.
     *
     * @param host     the server host or IP
     * @param port     the server TCP port
     * @param username the desired username
     * @throws IOException           if the connection cannot be established
     * @throws IllegalStateException if this client is already connected
     */
    public synchronized void connect(String host, int port, String username) throws IOException {
        if (connected.get()) {
            throw new IllegalStateException("client is already connected");
        }
        Objects.requireNonNull(username, "username");
        if (username.isBlank()) {
            throw new IllegalArgumentException("username must not be blank");
        }

        ServerConnection conn = ServerConnection.open(host, port, connectTimeoutMillis);
        this.connection = conn;
        this.sender = new MessageSender(conn.writer());
        this.username = username;
        this.readerExecutor = singleThreadExecutor("chat-client-reader");
        this.senderExecutor = singleThreadExecutor("chat-client-sender");

        closing.set(false);
        connected.set(true);

        this.reader = new MessageReader(conn.reader(), this::dispatchMessage, this::onReaderStopped);
        readerExecutor.submit(reader);

        LOG.info(() -> "Connected to " + conn.remoteAddress() + " as '" + username + "'");
        safeNotify(listener::onConnected);

        // Announce ourselves to the server (asynchronously, on the sender thread).
        submitSend(Message.login(username));
    }

    /**
     * Requests a graceful disconnect: best-effort {@code DISCONNECT} notice to the
     * server, then a full local teardown. Safe to call at any time, including when
     * not connected or more than once (subsequent calls are no-ops).
     */
    public void disconnect() {
        if (!connected.get()) {
            return;
        }
        // Best-effort, synchronous so it is flushed before we close the socket.
        MessageSender activeSender = this.sender;
        if (activeSender != null) {
            try {
                activeSender.send(Message.disconnect());
            } catch (IOException ex) {
                LOG.log(Level.FINE, ex, () -> "Ignoring failure to send graceful DISCONNECT");
            }
        }
        close(null, "disconnected");
    }

    /** @return {@code true} while the client has a live connection. */
    public boolean isConnected() {
        return connected.get();
    }

    /** @return the username used for the current/last connection, or {@code null}. */
    public String username() {
        return username;
    }

    // ---------------------------------------------------------------------
    // Sending
    // ---------------------------------------------------------------------

    /**
     * Sends a broadcast chat message to every other connected user.
     *
     * @param content the message text
     */
    public void sendGlobalMessage(String content) {
        submitSend(Message.global(username, content));
    }

    /**
     * Sends a private chat message to a single recipient.
     *
     * @param recipient the target username
     * @param content   the message text
     */
    public void sendPrivateMessage(String recipient, String content) {
        submitSend(Message.privateMessage(username, recipient, content));
    }

    /** Asks the server for the current roster of online usernames. */
    public void requestUserList() {
        submitSend(new Message(MessageType.USER_LIST, "", "", ""));
    }

    /**
     * Sends an arbitrary pre-built message. Exposed for flexibility (and tests);
     * the typed helpers above are preferred at call sites.
     *
     * @param message the message to send
     */
    public void send(Message message) {
        submitSend(Objects.requireNonNull(message, "message"));
    }

    // ---------------------------------------------------------------------
    // Internals
    // ---------------------------------------------------------------------

    /** Queues a write on the sender thread so the caller (e.g. the UI) never blocks. */
    private void submitSend(Message message) {
        if (!connected.get()) {
            safeNotify(() -> listener.onError(new IllegalStateException("not connected")));
            return;
        }
        ExecutorService exec = this.senderExecutor;
        if (exec == null) {
            return;
        }
        try {
            exec.submit(() -> {
                try {
                    sender.send(message);
                } catch (IOException ex) {
                    close(ex, "connection lost while sending: " + ex.getMessage());
                }
            });
        } catch (RejectedExecutionException ex) {
            // Sender executor is shutting down (teardown in progress) — drop quietly.
            LOG.log(Level.FINE, ex, () -> "Dropping send; client is closing");
        }
    }

    /** Reader-thread callback for each inbound message. */
    private void dispatchMessage(Message message) {
        listener.onMessage(message);
    }

    /**
     * Reader-thread callback invoked once when the read loop ends. Delegates to the
     * shared {@link #close(Throwable, String)} teardown (which is idempotent, so if
     * a local {@link #disconnect()} already ran this is a harmless no-op).
     *
     * @param error the cause the loop ended with, or {@code null} for a clean close
     */
    private void onReaderStopped(Throwable error) {
        if (error != null) {
            close(error, "connection lost: " + error.getMessage());
        } else {
            close(null, "server closed the connection");
        }
    }

    /**
     * The single, idempotent teardown path. The first caller wins (guarded by
     * {@link #closing}); it flips state, stops the reader, closes the socket
     * (unblocking {@code readLine}), shuts the executors down, and finally notifies
     * the listener &mdash; {@code onError} first (if any), then {@code onDisconnected}.
     *
     * <p>Executors are shut down with {@code shutdownNow()} and <em>not</em> awaited,
     * so this is safe to call from the reader thread itself without self-deadlock.
     */
    private void close(Throwable error, String reason) {
        if (!closing.compareAndSet(false, true)) {
            return; // teardown already in progress or complete
        }
        connected.set(false);

        if (reader != null) {
            reader.stop();
        }
        if (connection != null) {
            connection.close();
        }
        shutdownNow(readerExecutor);
        shutdownNow(senderExecutor);

        LOG.info(() -> "Disconnected: " + reason);
        if (error != null) {
            safeNotify(() -> listener.onError(error));
        }
        safeNotify(() -> listener.onDisconnected(reason));
    }

    /** Runs a listener callback, guarding against a misbehaving listener. */
    private void safeNotify(Runnable callback) {
        try {
            callback.run();
        } catch (RuntimeException ex) {
            LOG.log(Level.WARNING, ex, () -> "Listener threw from a callback");
        }
    }

    private static void shutdownNow(ExecutorService executor) {
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    private static ExecutorService singleThreadExecutor(String threadName) {
        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(runnable, threadName);
            thread.setDaemon(true); // never keep the JVM alive on our account
            return thread;
        };
        return Executors.newSingleThreadExecutor(factory);
    }
}
