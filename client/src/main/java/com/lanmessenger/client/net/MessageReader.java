package com.lanmessenger.client.net;

import com.lanmessenger.common.Message;

import java.io.BufferedReader;
import java.io.IOException;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The inbound half of the transport: a {@link Runnable} that continuously reads
 * protocol lines from the server and hands each decoded {@link Message} to a
 * consumer.
 *
 * <h2>Why a dedicated thread</h2>
 * <p>{@link BufferedReader#readLine()} blocks until a line arrives, so this loop
 * <b>must</b> run on its own background thread. Running it on the JavaFX
 * Application Thread would freeze the UI. {@link ChatClient} therefore submits
 * this reader to a dedicated single-thread executor; the {@code messageConsumer}
 * consequently fires off-thread, and the UI-facing
 * {@link com.lanmessenger.client.FxChatClientListener} is what re-marshals it
 * back onto the FX thread.
 *
 * <h2>Termination</h2>
 * <p>The loop ends when any of the following happens, and always reports exactly
 * once through {@code endConsumer}:
 * <ul>
 *   <li>the server closes the stream cleanly ({@code readLine()} returns
 *       {@code null}) &mdash; reported with a {@code null} cause;</li>
 *   <li>an {@link IOException} occurs while still running (e.g. the connection is
 *       reset) &mdash; reported with that exception as the cause;</li>
 *   <li>{@link #stop()} is called and the socket is closed, which unblocks the
 *       read; because this is an intentional stop, the resulting exception is
 *       treated as a clean end ({@code null} cause).</li>
 * </ul>
 *
 * <p>A single malformed line is logged and skipped rather than killing the
 * session, mirroring the server's tolerance for bad input.
 */
final class MessageReader implements Runnable {

    private static final Logger LOG = Logger.getLogger(MessageReader.class.getName());

    private final BufferedReader reader;
    private final Consumer<Message> messageConsumer;
    private final Consumer<Throwable> endConsumer;

    private volatile boolean running = true;

    /**
     * @param reader          the connection's line reader
     * @param messageConsumer receives each decoded message on this thread
     * @param endConsumer     invoked once when the loop ends; its argument is the
     *                        error that ended it, or {@code null} for a clean end
     */
    MessageReader(BufferedReader reader,
                  Consumer<Message> messageConsumer,
                  Consumer<Throwable> endConsumer) {
        this.reader = Objects.requireNonNull(reader, "reader");
        this.messageConsumer = Objects.requireNonNull(messageConsumer, "messageConsumer");
        this.endConsumer = Objects.requireNonNull(endConsumer, "endConsumer");
    }

    @Override
    public void run() {
        Throwable error = null;
        try {
            String line;
            while (running && (line = reader.readLine()) != null) {
                Message message = tryDecode(line);
                if (message != null) {
                    messageConsumer.accept(message);
                }
            }
        } catch (IOException ex) {
            // If we were asked to stop, the socket close caused this — expected.
            if (running) {
                error = ex;
            }
        } finally {
            endConsumer.accept(error);
        }
    }

    /**
     * Signals the loop to stop. The caller is expected to also close the socket,
     * which unblocks any in-progress {@link BufferedReader#readLine()}.
     */
    void stop() {
        running = false;
    }

    private static Message tryDecode(String line) {
        try {
            return Message.decode(line);
        } catch (IllegalArgumentException ex) {
            LOG.log(Level.FINE, ex, () -> "Skipping malformed line from server: " + line);
            return null;
        }
    }
}
