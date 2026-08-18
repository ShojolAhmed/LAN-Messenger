package com.lanmessenger.client.history;

import com.lanmessenger.client.ui.model.ChatMessage;

import java.util.List;
import java.util.function.Consumer;

/**
 * The UI-facing view of chat persistence: load a conversation's history and record
 * new messages, without the caller ever touching JDBC or a background thread.
 *
 * <p><b>Asynchronous by contract.</b> Loads take a callback that is invoked on the
 * JavaFX Application Thread when the (off-thread) query completes, so the UI never
 * blocks on the database. Records are fire-and-forget: they are queued and written
 * off-thread, and failures are logged rather than thrown.
 *
 * <p>Use {@link #open(String)} for a real, SQLite-backed history for a given user,
 * or {@link #disabled()} for a no-op history (used by the layout smoke test, and as
 * the graceful fallback if the database cannot be opened) &mdash; callers cannot
 * tell the difference, so persistence being unavailable never changes the UI's
 * behaviour beyond simply having no history to show.
 */
public interface ChatHistory {

    /** Loads the global room's history; {@code onLoaded} runs on the FX thread. */
    void loadGlobal(Consumer<List<ChatMessage>> onLoaded);

    /** Loads the history with {@code peer}; {@code onLoaded} runs on the FX thread. */
    void loadDirect(String peer, Consumer<List<ChatMessage>> onLoaded);

    /** Queues a global message to be persisted off-thread. */
    void recordGlobal(ChatMessage message);

    /** Queues a private message with {@code peer} to be persisted off-thread. */
    void recordDirect(String peer, ChatMessage message);

    /** Releases resources (closes the database and stops the background thread). */
    void close();

    /**
     * A real, SQLite-backed history for {@code owner}, stored under the local
     * application data directory. The database is opened lazily on the background
     * thread; if it cannot be opened, this instance degrades to a no-op (loads
     * return empty, records are dropped) so the application keeps working.
     */
    static ChatHistory open(String owner) {
        return PersistentChatHistory.open(owner);
    }

    /** A history that stores nothing and always loads an empty list. */
    static ChatHistory disabled() {
        return new ChatHistory() {
            @Override
            public void loadGlobal(Consumer<List<ChatMessage>> onLoaded) {
                onLoaded.accept(List.of());
            }

            @Override
            public void loadDirect(String peer, Consumer<List<ChatMessage>> onLoaded) {
                onLoaded.accept(List.of());
            }

            @Override
            public void recordGlobal(ChatMessage message) {
                // no-op
            }

            @Override
            public void recordDirect(String peer, ChatMessage message) {
                // no-op
            }

            @Override
            public void close() {
                // no-op
            }
        };
    }
}
