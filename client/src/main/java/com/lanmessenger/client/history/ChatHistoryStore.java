package com.lanmessenger.client.history;

import com.lanmessenger.client.data.ChatStorageException;
import com.lanmessenger.client.data.Database;
import com.lanmessenger.client.data.MessageDao;
import com.lanmessenger.client.data.StoredMessage;
import com.lanmessenger.client.data.UserDao;
import com.lanmessenger.client.ui.model.ChatMessage;
import com.lanmessenger.common.MessageType;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Synchronous bridge between the pure-JDBC {@code client.data} layer and the UI's
 * {@link ChatMessage} view-model. It owns a {@link Database} and its DAOs, and maps
 * in both directions for a single logged-in {@code owner}:
 *
 * <ul>
 *   <li>on <b>save</b>, a {@link ChatMessage} becomes a {@link StoredMessage} &mdash;
 *       an outgoing message is stored with {@code sender = owner}, an incoming one
 *       with the author as {@code sender};</li>
 *   <li>on <b>load</b>, a stored row becomes an outgoing bubble when its
 *       {@code sender} is the owner, otherwise an incoming bubble.</li>
 * </ul>
 *
 * <p>System notices (joins, leaves, "beginning of conversation", delivery
 * failures) are ephemeral and are never persisted.
 *
 * <p>This class is intentionally free of JavaFX and of any threading concerns: it
 * is driven from a single background thread by {@link PersistentChatHistory}, and
 * is easy to unit-test headlessly. All methods propagate
 * {@link ChatStorageException} (from the data layer) on failure.
 */
public final class ChatHistoryStore implements AutoCloseable {

    private static final String GLOBAL = MessageType.GLOBAL_MESSAGE.name();
    private static final String PRIVATE = MessageType.PRIVATE_MESSAGE.name();

    private final String owner;
    private final Database database;
    private final MessageDao messages;
    private final UserDao users;

    private ChatHistoryStore(String owner, Database database) {
        this.owner = owner;
        this.database = database;
        this.messages = new MessageDao(database);
        this.users = new UserDao(database);
    }

    /**
     * Opens the store for {@code owner}, backed by the SQLite database at
     * {@code file}, and records the owner in the users table.
     *
     * @throws ChatStorageException if the database cannot be opened
     */
    public static ChatHistoryStore open(String owner, Path file) {
        String normalisedOwner = owner == null ? "" : owner.trim();
        Database database = Database.open(file);
        ChatHistoryStore store = new ChatHistoryStore(normalisedOwner, database);
        store.users.markSeen(normalisedOwner, LocalDateTime.now());
        return store;
    }

    /** Persists a global message (ignored if it is a system notice). */
    public void saveGlobal(ChatMessage message) {
        if (message.isSystem()) {
            return;
        }
        String sender = message.isOutgoing() ? owner : message.author();
        messages.insert(StoredMessage.create(owner, sender, "", message.content(), GLOBAL, message.timestamp()));
        users.markSeen(sender, message.timestamp());
    }

    /** Persists a private message with {@code peer} (ignored if a system notice). */
    public void saveDirect(String peer, ChatMessage message) {
        if (message.isSystem()) {
            return;
        }
        String sender = message.isOutgoing() ? owner : peer;
        String recipient = message.isOutgoing() ? peer : owner;
        messages.insert(StoredMessage.create(owner, sender, recipient, message.content(), PRIVATE, message.timestamp()));
        users.markSeen(peer, message.timestamp());
    }

    /** Loads the owner's global history, oldest first. */
    public List<ChatMessage> loadGlobal() {
        return toChatMessages(messages.findGlobal(owner, GLOBAL));
    }

    /** Loads the owner's one-to-one history with {@code peer}, oldest first. */
    public List<ChatMessage> loadDirect(String peer) {
        return toChatMessages(messages.findDirect(owner, peer, PRIVATE));
    }

    private List<ChatMessage> toChatMessages(List<StoredMessage> rows) {
        List<ChatMessage> result = new ArrayList<>(rows.size());
        for (StoredMessage row : rows) {
            if (owner.equals(row.sender())) {
                result.add(ChatMessage.outgoing(row.content(), row.timestamp()));
            } else {
                result.add(ChatMessage.incoming(row.sender(), row.content(), row.timestamp()));
            }
        }
        return result;
    }

    @Override
    public void close() {
        database.close();
    }
}
