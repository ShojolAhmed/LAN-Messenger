package com.lanmessenger.client.net;

import com.lanmessenger.common.Message;

/**
 * Callback contract through which the {@link ChatClient} reports networking
 * events to the rest of the application.
 *
 * <h2>Threading</h2>
 * <p><b>These callbacks are invoked from the client's background networking
 * threads, never from the caller.</b> In particular {@link #onMessage(Message)}
 * fires on the dedicated reader thread as data arrives. Implementations must
 * therefore be thread-safe and, crucially, must <em>not</em> touch JavaFX
 * controls directly.
 *
 * <p>The UI never implements this interface against live controls. Instead it
 * wraps its handler in {@link com.lanmessenger.client.FxChatClientListener},
 * which hops every callback onto the JavaFX Application Thread via
 * {@link javafx.application.Platform#runLater(Runnable)}. Keeping the contract
 * here free of any JavaFX types is what lets the whole networking layer be
 * tested headlessly, with no UI toolkit running.
 *
 * <p>All methods except {@link #onMessage(Message)} are {@code default} no-ops so
 * that lightweight listeners (and tests) only override what they care about.
 */
public interface ChatClientListener {

    /**
     * Invoked once the TCP connection to the server has been established and the
     * {@code LOGIN} request has been sent. This signals transport-level success
     * only; whether the chosen username was accepted arrives later as a
     * {@link com.lanmessenger.common.MessageType#LOGIN_SUCCESS} or
     * {@link com.lanmessenger.common.MessageType#LOGIN_FAILED} message through
     * {@link #onMessage(Message)}.
     */
    default void onConnected() {
        // no-op by default
    }

    /**
     * Invoked for every message received from the server, in arrival order.
     *
     * @param message the decoded message (never {@code null})
     */
    void onMessage(Message message);

    /**
     * Invoked exactly once when the connection ends, whether because the client
     * disconnected, the server closed the socket, or an I/O error occurred. When
     * an error was the cause, {@link #onError(Throwable)} fires first.
     *
     * @param reason a short, human-readable explanation
     */
    default void onDisconnected(String reason) {
        // no-op by default
    }

    /**
     * Invoked when a networking error occurs (for example a failed send or a
     * dropped connection). For connection-fatal errors this is immediately
     * followed by {@link #onDisconnected(String)}.
     *
     * @param error the cause
     */
    default void onError(Throwable error) {
        // no-op by default
    }
}
