package com.lanmessenger.client.history;

import com.lanmessenger.client.data.Database;
import com.lanmessenger.client.data.UserDao;
import com.lanmessenger.client.ui.model.ChatMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the {@link ChatHistoryStore} mapping between the UI's {@link ChatMessage}
 * and stored rows, per-peer/owner scoping, participant recording, and &mdash; the
 * key persistence guarantee &mdash; that history survives a "restart" (a fresh store
 * opened on the same database file).
 */
class ChatHistoryStoreTest {

    private static final LocalDateTime T = LocalDateTime.of(2026, 5, 1, 12, 0, 0);

    @Test
    void savesAndLoadsGlobalPreservingDirection(@TempDir Path dir) {
        try (ChatHistoryStore store = ChatHistoryStore.open("alice", dir.resolve("g.db"))) {
            store.saveGlobal(ChatMessage.outgoing("hello all", T));
            store.saveGlobal(ChatMessage.incoming("bob", "hi alice", T.plusSeconds(1)));
            store.saveGlobal(ChatMessage.system("bob joined the channel.", T.plusSeconds(2))); // not stored

            List<ChatMessage> loaded = store.loadGlobal();
            assertEquals(2, loaded.size(), "system notices are not persisted");
            assertTrue(loaded.get(0).isOutgoing());
            assertEquals("hello all", loaded.get(0).content());
            assertFalse(loaded.get(1).isOutgoing());
            assertEquals("bob", loaded.get(1).author());
            assertEquals("hi alice", loaded.get(1).content());
        }
    }

    @Test
    void savesAndLoadsPrivateHistoryPerPeer(@TempDir Path dir) {
        try (ChatHistoryStore store = ChatHistoryStore.open("alice", dir.resolve("p.db"))) {
            store.saveDirect("bob", ChatMessage.outgoing("hey bob", T));
            store.saveDirect("bob", ChatMessage.incoming("bob", "hey alice", T.plusSeconds(1)));
            store.saveDirect("carol", ChatMessage.outgoing("hi carol", T.plusSeconds(2)));

            List<ChatMessage> withBob = store.loadDirect("bob");
            assertEquals(2, withBob.size());
            assertTrue(withBob.get(0).isOutgoing());
            assertFalse(withBob.get(1).isOutgoing());
            assertEquals("bob", withBob.get(1).author());

            List<ChatMessage> withCarol = store.loadDirect("carol");
            assertEquals(1, withCarol.size(), "carol's thread is separate from bob's");
            assertEquals("hi carol", withCarol.get(0).content());
        }
    }

    @Test
    void historySurvivesReopen(@TempDir Path dir) {
        Path db = dir.resolve("restart.db");
        try (ChatHistoryStore store = ChatHistoryStore.open("alice", db)) {
            store.saveGlobal(ChatMessage.outgoing("persist me", T));
            store.saveDirect("bob", ChatMessage.incoming("bob", "and me", T.plusSeconds(1)));
        }

        // Simulate closing and restarting the application: a brand-new store on the
        // same file must see the previously stored messages.
        try (ChatHistoryStore reopened = ChatHistoryStore.open("alice", db)) {
            List<ChatMessage> global = reopened.loadGlobal();
            assertEquals(1, global.size());
            assertEquals("persist me", global.get(0).content());

            List<ChatMessage> withBob = reopened.loadDirect("bob");
            assertEquals(1, withBob.size());
            assertEquals("and me", withBob.get(0).content());
        }
    }

    @Test
    void historyIsScopedToOwner(@TempDir Path dir) {
        Path db = dir.resolve("owners.db");
        try (ChatHistoryStore alice = ChatHistoryStore.open("alice", db)) {
            alice.saveGlobal(ChatMessage.outgoing("only in alice's view", T));
        }
        try (ChatHistoryStore bob = ChatHistoryStore.open("bob", db)) {
            assertTrue(bob.loadGlobal().isEmpty(), "a different local user keeps a separate history");
        }
    }

    @Test
    void recordsParticipantsInUsersTable(@TempDir Path dir) {
        Path db = dir.resolve("participants.db");
        try (ChatHistoryStore store = ChatHistoryStore.open("alice", db)) {
            store.saveGlobal(ChatMessage.incoming("bob", "hi", T));
            store.saveDirect("carol", ChatMessage.outgoing("hey", T.plusSeconds(1)));
        }
        try (Database database = Database.open(db)) {
            List<String> users = new UserDao(database).findUsernames();
            assertTrue(users.containsAll(List.of("alice", "bob", "carol")),
                    "self and both peers should be recorded: " + users);
        }
    }
}
