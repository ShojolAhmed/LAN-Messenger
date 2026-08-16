package com.lanmessenger.server;

import com.lanmessenger.common.Message;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Handles all communication with a single connected client on its own thread.
 *
 * <p>One {@code ClientHandler} is created per accepted socket and submitted to the
 * server's thread pool, so every client is serviced independently. The handler:
 * <ol>
 *   <li>reads the protocol line-by-line ({@link BufferedReader#readLine()}),</li>
 *   <li>enforces a simple state machine &mdash; a client must {@code LOGIN}
 *       before it can chat,</li>
 *   <li>dispatches each {@link Message} to the right action, and</li>
 *   <li>always cleans up (deregister + notify others + close socket) in a
 *       {@code finally} block, no matter how the connection ends.</li>
 * </ol>
 *
 * <p><b>Crash isolation:</b> every failure mode for <em>this</em> socket
 * (a dropped connection, a malformed line, a broken pipe on write) is caught and
 * confined to this thread. One misbehaving client can therefore never take down
 * the server or affect other clients.
 */
public final class ClientHandler implements Runnable {

    private static final Logger LOG = Logger.getLogger(ClientHandler.class.getName());

    /** Allowed username characters and length, validated at login. */
    private static final int MAX_USERNAME_LENGTH = 24;
    private static final String USERNAME_PATTERN = "[A-Za-z0-9._-]{1," + MAX_USERNAME_LENGTH + "}";

    private final Socket socket;
    private final ClientManager clientManager;
    private final String remoteAddress;
    private final BufferedReader in;
    private final PrintWriter out;

    /** The logged-in username, or {@code null} until a successful LOGIN. */
    private volatile String username;
    private volatile boolean running = true;

    /**
     * Wraps an accepted socket and prepares its UTF-8 text streams.
     *
     * @throws IOException if the socket's streams cannot be opened
     */
    public ClientHandler(Socket socket, ClientManager clientManager) throws IOException {
        this.socket = socket;
        this.clientManager = clientManager;
        this.remoteAddress = String.valueOf(socket.getRemoteSocketAddress());
        this.in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
        this.out = new PrintWriter(new java.io.OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true);
    }

    @Override
    public void run() {
        LOG.info(() -> "Client connected: " + remoteAddress);
        try {
            String line;
            while (running && (line = in.readLine()) != null) {
                handleLine(line);
            }
        } catch (IOException ex) {
            // Expected when a client drops or the socket is closed during shutdown.
            LOG.log(Level.FINE, ex, () -> "Connection ended for " + describe());
        } finally {
            cleanup();
        }
    }

    /**
     * Sends a message to this client. Safe to call from any thread (e.g. another
     * client's handler during a broadcast); writes are serialised so lines never
     * interleave. If the write fails, the connection is closed so the read loop
     * can perform its normal cleanup.
     */
    public synchronized void send(Message message) {
        if (out == null) {
            return;
        }
        out.println(message.encode());
        if (out.checkError()) {
            LOG.fine(() -> "Write failed to " + describe() + "; closing connection");
            close();
        }
    }

    /**
     * Closes the connection. Idempotent and quiet: closing the socket unblocks the
     * {@link #run()} read loop, which then runs the shared cleanup path.
     */
    public void close() {
        running = false;
        try {
            socket.close();
        } catch (IOException ignored) {
            // Already closed or never fully opened — nothing useful to do.
        }
    }

    /** @return the logged-in username, or {@code null} if not yet logged in. */
    public String getUsername() {
        return username;
    }

    // ---------------------------------------------------------------------
    // Protocol dispatch
    // ---------------------------------------------------------------------

    private void handleLine(String line) {
        Message message;
        try {
            message = Message.decode(line);
        } catch (IllegalArgumentException ex) {
            // A single bad line is reported but does not end the session.
            send(Message.error("malformed message: " + ex.getMessage()));
            return;
        }

        if (username == null) {
            handleBeforeLogin(message);
        } else {
            handleAfterLogin(message);
        }
    }

    private void handleBeforeLogin(Message message) {
        if (message.type() != com.lanmessenger.common.MessageType.LOGIN) {
            send(Message.error("please LOGIN before sending other messages"));
            return;
        }
        attemptLogin(message.sender());
    }

    private void attemptLogin(String requestedName) {
        String name = requestedName == null ? "" : requestedName.trim();

        if (!name.matches(USERNAME_PATTERN)) {
            send(Message.loginFailed("invalid username (use 1-" + MAX_USERNAME_LENGTH
                    + " chars: letters, digits, '.', '_', '-')"));
            return;
        }
        if (!clientManager.register(name, this)) {
            send(Message.loginFailed("username '" + name + "' is already taken"));
            return;
        }

        this.username = name;
        send(Message.loginSuccess("welcome, " + name));
        send(Message.userList(clientManager.usernames()));
        clientManager.broadcast(Message.userJoined(name), name);
        LOG.info(() -> "User '" + name + "' logged in from " + remoteAddress);
    }

    private void handleAfterLogin(Message message) {
        switch (message.type()) {
            case GLOBAL_MESSAGE ->
                    clientManager.broadcast(Message.global(username, message.content()), username);
            case PRIVATE_MESSAGE -> deliverPrivate(message);
            case USER_LIST -> send(Message.userList(clientManager.usernames()));
            case DISCONNECT -> {
                LOG.fine(() -> "User '" + username + "' requested disconnect");
                running = false;
            }
            case LOGIN -> send(Message.error("already logged in as '" + username + "'"));
            default -> send(Message.error("unsupported message type: " + message.type()));
        }
    }

    private void deliverPrivate(Message message) {
        String recipient = message.recipient();
        if (recipient.isEmpty()) {
            send(Message.error("private message requires a recipient"));
            return;
        }
        boolean delivered = clientManager.sendToUser(
                recipient, Message.privateMessage(username, recipient, message.content()));
        if (!delivered) {
            send(Message.error("user '" + recipient + "' is not online"));
        }
    }

    // ---------------------------------------------------------------------
    // Teardown
    // ---------------------------------------------------------------------

    private void cleanup() {
        String name = username;
        if (name != null) {
            clientManager.remove(name, this);
            clientManager.broadcast(Message.userLeft(name));
        }
        close();
        LOG.info(() -> "Client disconnected: " + describe());
    }

    private String describe() {
        return (username != null ? "'" + username + "' " : "") + remoteAddress;
    }
}
