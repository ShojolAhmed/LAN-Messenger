package com.lanmessenger.client.history;

import com.lanmessenger.client.ui.model.ChatMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the asynchronous {@link PersistentChatHistory} wrapper: it records and
 * loads through a background executor (here driven with an inline "UI" executor so
 * no JavaFX toolkit is needed), and degrades to a harmless no-op when the database
 * cannot be opened &mdash; so a storage failure never crashes the application.
 */
class PersistentChatHistoryTest {

    @Test
    void recordsThenLoadsAsynchronously(@TempDir Path dir) throws Exception {
        ExecutorService dbExecutor = singleThreadExecutor();
        // uiExecutor runs the callback inline, avoiding a JavaFX Application Thread.
        PersistentChatHistory history =
                new PersistentChatHistory("alice", dir.resolve("async.db"), dbExecutor, Runnable::run);

        history.recordGlobal(ChatMessage.outgoing("saved off-thread", LocalDateTime.now()));

        List<ChatMessage> loaded = awaitGlobal(history);
        assertEquals(1, loaded.size());
        assertEquals("saved off-thread", loaded.get(0).content());
        assertTrue(loaded.get(0).isOutgoing());

        history.close();
        assertTrue(dbExecutor.awaitTermination(5, TimeUnit.SECONDS));
    }

    @Test
    void degradesToNoOpWhenDatabaseCannotOpen(@TempDir Path dir) throws Exception {
        // An ancestor of the path is a regular file, so the store cannot be opened.
        Path blocker = Files.createFile(dir.resolve("blocker"));
        Path unopenable = blocker.resolve("nested").resolve("chat.db");

        ExecutorService dbExecutor = singleThreadExecutor();
        PersistentChatHistory history =
                new PersistentChatHistory("alice", unopenable, dbExecutor, Runnable::run);

        assertDoesNotThrow(() ->
                history.recordGlobal(ChatMessage.outgoing("dropped", LocalDateTime.now())));

        List<ChatMessage> loaded = awaitGlobal(history);
        assertTrue(loaded.isEmpty(), "a failed store must load empty history, not throw");

        history.close();
        assertTrue(dbExecutor.awaitTermination(5, TimeUnit.SECONDS));
    }

    private static List<ChatMessage> awaitGlobal(PersistentChatHistory history) throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<List<ChatMessage>> ref = new AtomicReference<>(List.of());
        history.loadGlobal(list -> {
            ref.set(list);
            latch.countDown();
        });
        assertTrue(latch.await(5, TimeUnit.SECONDS), "load callback did not fire");
        return ref.get();
    }

    private static ExecutorService singleThreadExecutor() {
        return Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "test-chat-history");
            thread.setDaemon(true);
            return thread;
        });
    }
}
