package com.lanmessenger.client.net;

import com.lanmessenger.common.Message;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.Objects;

/**
 * Writes {@link Message}s to the server as encoded protocol lines.
 *
 * <p>This is the outbound half of the transport, and the client counterpart of
 * the server's {@code ClientHandler.send(..)}. It is intentionally tiny: it
 * {@linkplain Message#encode() encodes} a message to a single line and
 * {@code println}s it through the connection's auto-flushing writer.
 *
 * <h2>Thread-safety</h2>
 * <p>{@link #send(Message)} is {@code synchronized} so that concurrent callers
 * (for example a queued chat message and a graceful {@code DISCONNECT} issued
 * from another thread) can never interleave partial lines on the wire.
 *
 * <p>A {@link PrintWriter} never throws on I/O failure; it records an internal
 * error flag instead. This class checks that flag after each write and surfaces
 * a real {@link IOException}, so the caller ({@link ChatClient}) can react by
 * tearing the connection down rather than silently losing messages.
 */
public final class MessageSender {

    private final PrintWriter writer;

    /**
     * @param writer the connection's auto-flushing, UTF-8 writer (never {@code null})
     */
    public MessageSender(PrintWriter writer) {
        this.writer = Objects.requireNonNull(writer, "writer");
    }

    /**
     * Encodes and sends one message, flushing it immediately.
     *
     * @param message the message to send (never {@code null})
     * @throws IOException if the underlying stream has failed (e.g. the connection
     *                     was reset), so the caller can treat the send as fatal
     */
    public synchronized void send(Message message) throws IOException {
        Objects.requireNonNull(message, "message");
        writer.println(message.encode());
        if (writer.checkError()) {
            throw new IOException("failed to send " + message.type() + " message to server");
        }
    }
}
