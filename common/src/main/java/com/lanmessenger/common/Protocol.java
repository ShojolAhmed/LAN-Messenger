package com.lanmessenger.common;

/**
 * Shared, protocol-level constants for the LAN Messenger.
 *
 * <p>Both the client and the server depend on this class so that they always
 * agree on values such as the TCP port. Keeping these constants in the
 * {@code common} module prevents the client and server from drifting apart.
 *
 * <p>This class only holds constants, so it is declared {@code final} and its
 * constructor is private to prevent instantiation.
 */
public final class Protocol {

    /** Human-readable application name, shown in the UI and logs. */
    public static final String APP_NAME = "LAN Messenger";

    /** Application version. */
    public static final String APP_VERSION = "1.0";

    /** Default TCP port the server listens on and clients connect to. */
    public static final int DEFAULT_PORT = 5000;

    private Protocol() {
        // Utility class: not meant to be instantiated.
    }
}
