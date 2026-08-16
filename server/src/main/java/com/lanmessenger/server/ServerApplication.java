package com.lanmessenger.server;

import com.lanmessenger.common.Protocol;

import java.io.IOException;
import java.util.logging.Logger;

/**
 * Command-line entry point for the LAN Messenger server.
 *
 * <p>Its job is deliberately small: read the {@link ServerConfiguration}, build a
 * {@link ChatServer}, register a JVM shutdown hook so {@code Ctrl+C} stops the
 * server cleanly, start it, and then wait. All the real work lives in the
 * collaborating classes ({@link ChatServer}, {@link ClientHandler},
 * {@link ClientManager}), which keeps each class easy to explain on its own.
 *
 * <p>Run it with:
 * <pre>{@code   mvn -pl server exec:java            # default port 5000
 *   mvn -pl server exec:java -Dexec.args="5050"   # custom port}</pre>
 */
public final class ServerApplication {

    private static final Logger LOG = Logger.getLogger(ServerApplication.class.getName());

    public static void main(String[] args) {
        configureLogging();

        final ServerConfiguration config;
        try {
            config = ServerConfiguration.fromArgs(args);
        } catch (IllegalArgumentException ex) {
            System.err.println("Invalid configuration: " + ex.getMessage());
            System.err.println("Usage: server [--port <1-65535>]  (default " + Protocol.DEFAULT_PORT + ")");
            System.exit(2);
            return;
        }

        ChatServer server = new ChatServer(config);

        // Ensure a clean shutdown on Ctrl+C / SIGTERM.
        Runtime.getRuntime().addShutdownHook(new Thread(server::shutdown, "shutdown-hook"));

        try {
            server.start();
        } catch (IOException ex) {
            LOG.severe("Could not start server on port " + config.port() + ": " + ex.getMessage());
            System.exit(1);
            return;
        }

        LOG.info(() -> Protocol.APP_NAME + " server v" + Protocol.APP_VERSION
                + " ready on port " + server.getBoundPort() + " (press Ctrl+C to stop)");

        try {
            server.awaitTermination();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            server.shutdown();
        }
    }

    /** Configures {@code java.util.logging} to emit compact single-line records. */
    private static void configureLogging() {
        if (System.getProperty("java.util.logging.SimpleFormatter.format") == null) {
            System.setProperty("java.util.logging.SimpleFormatter.format",
                    "%1$tF %1$tT %4$-7s [%3$s] %5$s%6$s%n");
        }
    }

    private ServerApplication() {
        // Entry-point class: not meant to be instantiated.
    }
}
