package com.lanmessenger.server;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The core TCP chat server: it owns the {@link ServerSocket}, accepts incoming
 * connections, and hands each one to a {@link ClientHandler} running on a pooled
 * thread.
 *
 * <h2>Threading model</h2>
 * <ul>
 *   <li>A single <b>acceptor thread</b> runs the {@code accept()} loop so that
 *       {@link #start()} can return immediately (handy for both the real
 *       application and tests).</li>
 *   <li>A <b>cached thread pool</b> supplies one worker thread per active client;
 *       idle threads are reused, which keeps the "thread per connection" model
 *       simple without leaking threads.</li>
 *   <li>Shared client state lives in one {@link ClientManager}, whose
 *       thread-safe collection is the only synchronisation the design needs.</li>
 * </ul>
 *
 * <h2>Lifecycle</h2>
 * {@link #start()} binds and begins accepting; {@link #shutdown()} closes the
 * listening socket (unblocking {@code accept()}), disconnects every client, and
 * drains the pool. {@code shutdown()} is idempotent so it is safe to call from a
 * JVM shutdown hook and from tests.
 */
public final class ChatServer {

    private static final Logger LOG = Logger.getLogger(ChatServer.class.getName());

    /** How long to wait for in-flight client threads to finish on shutdown. */
    private static final int SHUTDOWN_GRACE_SECONDS = 5;

    private final ServerConfiguration config;
    private final ClientManager clientManager = new ClientManager();

    private ServerSocket serverSocket;
    private ExecutorService clientPool;
    private Thread acceptorThread;
    private volatile boolean running;

    public ChatServer(ServerConfiguration config) {
        this.config = config;
    }

    /**
     * Binds the server socket and starts accepting connections on a background
     * acceptor thread. Returns as soon as the socket is listening.
     *
     * @throws IOException           if the port cannot be bound
     * @throws IllegalStateException if the server is already running
     */
    public synchronized void start() throws IOException {
        if (running) {
            throw new IllegalStateException("Server is already running");
        }
        serverSocket = new ServerSocket(config.port());
        clientPool = Executors.newCachedThreadPool(namedThreadFactory());
        running = true;

        acceptorThread = new Thread(this::acceptLoop, "chat-acceptor");
        acceptorThread.start();

        LOG.info(() -> "Chat server listening on port " + getBoundPort());
    }

    /**
     * Blocks the calling thread until the server stops accepting connections
     * (i.e. until {@link #shutdown()} runs). Used by the application entry point
     * to keep the process alive.
     */
    public void awaitTermination() throws InterruptedException {
        Thread acceptor = this.acceptorThread;
        if (acceptor != null) {
            acceptor.join();
        }
    }

    /**
     * Gracefully stops the server: stops accepting, disconnects all clients, and
     * shuts the worker pool down. Safe to call more than once.
     */
    public synchronized void shutdown() {
        if (!running) {
            return;
        }
        LOG.info("Shutting down chat server...");
        running = false;

        // Closing the listening socket makes the blocked accept() throw, which
        // ends the acceptor loop.
        try {
            if (serverSocket != null) {
                serverSocket.close();
            }
        } catch (IOException ex) {
            LOG.log(Level.FINE, "Error closing server socket", ex);
        }

        clientManager.disconnectAll();
        drainPool();

        try {
            if (acceptorThread != null) {
                acceptorThread.join(TimeUnit.SECONDS.toMillis(SHUTDOWN_GRACE_SECONDS));
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }

        LOG.info("Chat server stopped");
    }

    /** @return the port the server is actually bound to (useful when port 0 was requested). */
    public int getBoundPort() {
        return serverSocket != null ? serverSocket.getLocalPort() : config.port();
    }

    /** @return {@code true} while the server is accepting connections. */
    public boolean isRunning() {
        return running;
    }

    /** @return the shared client registry (exposed for inspection and tests). */
    public ClientManager clientManager() {
        return clientManager;
    }

    // ---------------------------------------------------------------------
    // Internals
    // ---------------------------------------------------------------------

    private void acceptLoop() {
        while (running) {
            final Socket socket;
            try {
                socket = serverSocket.accept();
            } catch (IOException ex) {
                if (running) {
                    LOG.log(Level.WARNING, "Acceptor stopping after error", ex);
                }
                break; // Socket closed by shutdown(), or a fatal listen error.
            }
            dispatch(socket);
        }
    }

    private void dispatch(Socket socket) {
        try {
            ClientHandler handler = new ClientHandler(socket, clientManager);
            clientPool.submit(handler);
        } catch (IOException ex) {
            // Failing to set up ONE client must never stop the server.
            LOG.warning("Failed to initialise client " + socket.getRemoteSocketAddress()
                    + ": " + ex.getMessage());
            closeQuietly(socket);
        } catch (RejectedExecutionException ex) {
            // Pool is shutting down; refuse the late connection cleanly.
            closeQuietly(socket);
        }
    }

    private void drainPool() {
        if (clientPool == null) {
            return;
        }
        clientPool.shutdown();
        try {
            if (!clientPool.awaitTermination(SHUTDOWN_GRACE_SECONDS, TimeUnit.SECONDS)) {
                clientPool.shutdownNow();
            }
        } catch (InterruptedException ex) {
            clientPool.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private static void closeQuietly(Socket socket) {
        try {
            socket.close();
        } catch (IOException ignored) {
            // Nothing useful to do.
        }
    }

    private static ThreadFactory namedThreadFactory() {
        AtomicInteger counter = new AtomicInteger(1);
        return runnable -> {
            Thread thread = new Thread(runnable, "chat-client-" + counter.getAndIncrement());
            thread.setDaemon(false);
            return thread;
        };
    }
}
