package com.lanmessenger.client.history;

import com.lanmessenger.client.data.AppDirectories;
import com.lanmessenger.client.ui.model.ChatMessage;
import javafx.application.Platform;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Asynchronous, SQLite-backed {@link ChatHistory}.
 *
 * <p>All database work is confined to a single-threaded executor, which keeps the
 * (not thread-safe) JDBC connection safe and guarantees the JavaFX Application
 * Thread never blocks on a query. Load results are delivered back through the
 * {@code uiExecutor} (normally {@link Platform#runLater}).
 *
 * <p>The underlying {@link ChatHistoryStore} is opened <em>lazily</em> on the
 * background thread the first time it is needed. If opening fails (for example the
 * data directory is not writable), the failure is logged once and this instance
 * quietly becomes a no-op: loads deliver an empty list and records are dropped. The
 * application therefore never crashes because the database cannot be opened.
 *
 * <p>The executors are injectable so the class can be driven synchronously in
 * headless tests (a direct executor for both).
 */
final class PersistentChatHistory implements ChatHistory {

    private static final Logger LOG = Logger.getLogger(PersistentChatHistory.class.getName());

    private final String owner;
    private final Path databaseFile;
    private final ExecutorService dbExecutor;
    private final Executor uiExecutor;

    // Touched only on the dbExecutor thread, so no synchronisation is needed.
    private boolean openAttempted;
    private ChatHistoryStore store;

    PersistentChatHistory(String owner, Path databaseFile, ExecutorService dbExecutor, Executor uiExecutor) {
        this.owner = owner == null ? "" : owner;
        this.databaseFile = databaseFile;
        this.dbExecutor = dbExecutor;
        this.uiExecutor = uiExecutor;
    }

    static PersistentChatHistory open(String owner) {
        ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "chat-history");
            thread.setDaemon(true);
            return thread;
        });
        return new PersistentChatHistory(owner, AppDirectories.chatDatabaseFile(), executor, Platform::runLater);
    }

    @Override
    public void loadGlobal(Consumer<List<ChatMessage>> onLoaded) {
        submitLoad(ChatHistoryStore::loadGlobal, onLoaded);
    }

    @Override
    public void loadDirect(String peer, Consumer<List<ChatMessage>> onLoaded) {
        submitLoad(current -> current.loadDirect(peer), onLoaded);
    }

    @Override
    public void recordGlobal(ChatMessage message) {
        submit(current -> current.saveGlobal(message), "record global message");
    }

    @Override
    public void recordDirect(String peer, ChatMessage message) {
        submit(current -> current.saveDirect(peer, message), "record private message");
    }

    @Override
    public void close() {
        execute(() -> {
            if (store != null) {
                try {
                    store.close();
                } catch (RuntimeException e) {
                    LOG.log(Level.FINE, e, () -> "Failed to close chat history store");
                }
                store = null;
            }
        });
        dbExecutor.shutdown();
    }

    // ---- Internals (the lambdas below run on the dbExecutor thread) ----

    private void submitLoad(Function<ChatHistoryStore, List<ChatMessage>> query,
                            Consumer<List<ChatMessage>> onLoaded) {
        execute(() -> {
            List<ChatMessage> result = List.of();
            ChatHistoryStore current = store();
            if (current != null) {
                try {
                    result = query.apply(current);
                } catch (RuntimeException e) {
                    LOG.log(Level.WARNING, e, () -> "Failed to load chat history");
                }
            }
            List<ChatMessage> delivered = result;
            uiExecutor.execute(() -> onLoaded.accept(delivered));
        });
    }

    private void submit(Consumer<ChatHistoryStore> action, String description) {
        execute(() -> {
            ChatHistoryStore current = store();
            if (current != null) {
                try {
                    action.accept(current);
                } catch (RuntimeException e) {
                    LOG.log(Level.WARNING, e, () -> "Failed to " + description);
                }
            }
        });
    }

    /** Lazily opens the store; returns {@code null} if opening failed. */
    private ChatHistoryStore store() {
        if (!openAttempted) {
            openAttempted = true;
            try {
                store = ChatHistoryStore.open(owner, databaseFile);
            } catch (RuntimeException e) {
                LOG.log(Level.WARNING, e,
                        () -> "Chat history persistence is disabled (could not open database)");
                store = null;
            }
        }
        return store;
    }

    private void execute(Runnable task) {
        try {
            dbExecutor.execute(task);
        } catch (RejectedExecutionException e) {
            // The executor has been shut down (close() was called); nothing to do.
            LOG.log(Level.FINE, e, () -> "Chat history task rejected; store is closing");
        }
    }
}
