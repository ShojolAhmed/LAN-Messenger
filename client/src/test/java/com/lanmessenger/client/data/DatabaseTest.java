package com.lanmessenger.client.data;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises the pure-JDBC data layer against a real (temporary) SQLite database:
 * inserting and reading global/private messages, the users registry, and graceful
 * failure when the database cannot be opened.
 */
class DatabaseTest {

    private static final String GLOBAL = "GLOBAL_MESSAGE";
    private static final String PRIVATE = "PRIVATE_MESSAGE";
    private static final LocalDateTime T = LocalDateTime.of(2026, 1, 2, 3, 4, 5);

    @Test
    void storesAndReadsGlobalAndPrivateMessages(@TempDir Path dir) {
        try (Database db = Database.open(dir.resolve("chat.db"))) {
            MessageDao messages = new MessageDao(db);

            messages.insert(StoredMessage.create("alice", "alice", "", "hello all", GLOBAL, T));
            messages.insert(StoredMessage.create("alice", "bob", "", "hi alice", GLOBAL, T.plusSeconds(1)));
            messages.insert(StoredMessage.create("alice", "alice", "bob", "pssst", PRIVATE, T.plusSeconds(2)));
            messages.insert(StoredMessage.create("alice", "bob", "alice", "what?", PRIVATE, T.plusSeconds(3)));
            messages.insert(StoredMessage.create("alice", "alice", "carol", "hey carol", PRIVATE, T.plusSeconds(4)));

            List<StoredMessage> global = messages.findGlobal("alice", GLOBAL);
            assertEquals(2, global.size());
            assertEquals("hello all", global.get(0).content());
            assertEquals("hi alice", global.get(1).content());

            List<StoredMessage> withBob = messages.findDirect("alice", "bob", PRIVATE);
            assertEquals(2, withBob.size(), "both directions of the alice<->bob thread");
            assertEquals("pssst", withBob.get(0).content());
            assertEquals("what?", withBob.get(1).content());

            List<StoredMessage> withCarol = messages.findDirect("alice", "carol", PRIVATE);
            assertEquals(1, withCarol.size(), "carol's thread must not include bob's messages");
        }
    }

    @Test
    void insertReturnsGeneratedId(@TempDir Path dir) {
        try (Database db = Database.open(dir.resolve("ids.db"))) {
            StoredMessage saved = new MessageDao(db)
                    .insert(StoredMessage.create("u", "u", "", "x", GLOBAL, T));
            assertTrue(saved.id() > 0, "primary key should be assigned by the database");
        }
    }

    @Test
    void usersUpsertIsIdempotent(@TempDir Path dir) {
        try (Database db = Database.open(dir.resolve("users.db"))) {
            UserDao users = new UserDao(db);
            users.markSeen("alice", T);
            users.markSeen("bob", T);
            users.markSeen("alice", T.plusHours(1)); // refresh, not a duplicate row
            users.markSeen("", T);                    // blank ignored

            List<String> names = users.findUsernames();
            assertEquals(List.of("alice", "bob"), names);
        }
    }

    @Test
    void openFailsGracefullyWithChatStorageException(@TempDir Path dir) throws Exception {
        // An ancestor of the database path is a regular file, so the data directory
        // cannot be created and opening must fail with the layer's own exception
        // rather than a raw SQLException / IOException.
        Path blocker = Files.createFile(dir.resolve("blocker"));
        Path unopenable = blocker.resolve("nested").resolve("chat.db");
        assertThrows(ChatStorageException.class, () -> Database.open(unopenable));
    }
}
