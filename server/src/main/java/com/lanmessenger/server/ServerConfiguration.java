package com.lanmessenger.server;

import com.lanmessenger.common.Protocol;

/**
 * Immutable holder for the server's runtime settings.
 *
 * <p>Right now the only setting is the TCP port, but keeping it in a dedicated
 * type (rather than passing a bare {@code int} around) means new options can be
 * added later without changing every constructor signature.
 *
 * <p>The port is resolved with a simple, predictable precedence:
 * <ol>
 *   <li>an explicit command-line argument ({@code --port <n>}, {@code -p <n>} or
 *       a lone numeric argument);</li>
 *   <li>the {@code lanmessenger.port} system property;</li>
 *   <li>otherwise {@link Protocol#DEFAULT_PORT} (5000).</li>
 * </ol>
 */
public final class ServerConfiguration {

    /** System property that can override the default port. */
    public static final String PORT_PROPERTY = "lanmessenger.port";

    /** Highest valid TCP port number. */
    private static final int MAX_PORT = 65535;

    private final int port;

    /**
     * @param port the TCP port to bind to; {@code 0} means "any free port"
     *             (useful for tests), otherwise it must be in {@code 1..65535}
     * @throws IllegalArgumentException if the port is out of range
     */
    public ServerConfiguration(int port) {
        if (port < 0 || port > MAX_PORT) {
            throw new IllegalArgumentException("Port out of range (0-" + MAX_PORT + "): " + port);
        }
        this.port = port;
    }

    /** @return a configuration using the {@link Protocol#DEFAULT_PORT default port}. */
    public static ServerConfiguration defaults() {
        return new ServerConfiguration(Protocol.DEFAULT_PORT);
    }

    /**
     * Builds a configuration from command-line arguments, falling back to the
     * system property and finally the default port.
     *
     * @param args the raw program arguments (may be empty)
     * @return the resolved configuration
     * @throws IllegalArgumentException if a supplied port value is not a valid number/range
     */
    public static ServerConfiguration fromArgs(String[] args) {
        Integer fromArgs = parsePortArgument(args);
        if (fromArgs != null) {
            return new ServerConfiguration(fromArgs);
        }

        String property = System.getProperty(PORT_PROPERTY);
        if (property != null && !property.isBlank()) {
            return new ServerConfiguration(parsePort(property.trim()));
        }

        return defaults();
    }

    /** @return the configured TCP port. */
    public int port() {
        return port;
    }

    @Override
    public String toString() {
        return "ServerConfiguration[port=" + port + "]";
    }

    private static Integer parsePortArgument(String[] args) {
        if (args == null) {
            return null;
        }
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (arg == null) {
                continue;
            }
            if (("--port".equals(arg) || "-p".equals(arg)) && i + 1 < args.length) {
                return parsePort(args[i + 1].trim());
            }
            if (arg.matches("\\d+")) {
                return parsePort(arg);
            }
        }
        return null;
    }

    private static int parsePort(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("Invalid port value: '" + value + "'");
        }
    }
}
