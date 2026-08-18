package com.lanmessenger.client.data;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Data-access object for the {@code messages} table.
 *
 * <p>Reconstructing conversations does not need a UI-specific "conversation id":
 * the {@code owner}, {@code sender}, {@code recipient} and {@code type} columns are
 * enough. Global history is every {@code GLOBAL_MESSAGE} for the owner; a private
 * thread with a peer is every {@code PRIVATE_MESSAGE} exchanged in either direction
 * between the owner and that peer.
 *
 * <p>Timestamps are stored as ISO-8601 text ({@link LocalDateTime#toString()}),
 * which sorts chronologically as text; {@code id} is the tie-breaker so messages
 * saved within the same instant keep their insertion order.
 */
public final class MessageDao {

    private static final String INSERT =
            "INSERT INTO messages(owner, sender, recipient, content, type, timestamp) "
                    + "VALUES(?, ?, ?, ?, ?, ?)";

    private static final String COLUMNS = "id, owner, sender, recipient, content, type, timestamp";

    private static final String SELECT_GLOBAL =
            "SELECT " + COLUMNS + " FROM messages "
                    + "WHERE owner = ? AND type = ? "
                    + "ORDER BY timestamp ASC, id ASC";

    private static final String SELECT_DIRECT =
            "SELECT " + COLUMNS + " FROM messages "
                    + "WHERE owner = ? AND type = ? "
                    + "AND ((sender = ? AND recipient = ?) OR (sender = ? AND recipient = ?)) "
                    + "ORDER BY timestamp ASC, id ASC";

    private final Database database;

    public MessageDao(Database database) {
        this.database = Objects.requireNonNull(database, "database");
    }

    /**
     * Inserts a message and returns it with the database-assigned {@code id}.
     *
     * @throws ChatStorageException if the insert fails
     */
    public StoredMessage insert(StoredMessage message) {
        try (PreparedStatement statement =
                     database.connection().prepareStatement(INSERT, Statement.RETURN_GENERATED_KEYS)) {
            statement.setString(1, message.owner());
            statement.setString(2, message.sender());
            statement.setString(3, message.recipient());
            statement.setString(4, message.content());
            statement.setString(5, message.type());
            statement.setString(6, message.timestamp().toString());
            statement.executeUpdate();

            long id = message.id();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                if (keys.next()) {
                    id = keys.getLong(1);
                }
            }
            return new StoredMessage(id, message.owner(), message.sender(), message.recipient(),
                    message.content(), message.type(), message.timestamp());
        } catch (SQLException e) {
            throw new ChatStorageException("Could not store message", e);
        }
    }

    /** Global history for {@code owner}, oldest first. */
    public List<StoredMessage> findGlobal(String owner, String type) {
        try (PreparedStatement statement = database.connection().prepareStatement(SELECT_GLOBAL)) {
            statement.setString(1, owner);
            statement.setString(2, type);
            return readAll(statement);
        } catch (SQLException e) {
            throw new ChatStorageException("Could not load global history", e);
        }
    }

    /** One-to-one history between {@code owner} and {@code peer}, oldest first. */
    public List<StoredMessage> findDirect(String owner, String peer, String type) {
        try (PreparedStatement statement = database.connection().prepareStatement(SELECT_DIRECT)) {
            statement.setString(1, owner);
            statement.setString(2, type);
            statement.setString(3, owner);
            statement.setString(4, peer);
            statement.setString(5, peer);
            statement.setString(6, owner);
            return readAll(statement);
        } catch (SQLException e) {
            throw new ChatStorageException("Could not load private history with " + peer, e);
        }
    }

    private static List<StoredMessage> readAll(PreparedStatement statement) throws SQLException {
        try (ResultSet rows = statement.executeQuery()) {
            List<StoredMessage> result = new ArrayList<>();
            while (rows.next()) {
                result.add(map(rows));
            }
            return result;
        }
    }

    private static StoredMessage map(ResultSet row) throws SQLException {
        return new StoredMessage(
                row.getLong("id"),
                row.getString("owner"),
                row.getString("sender"),
                row.getString("recipient"),
                row.getString("content"),
                row.getString("type"),
                parseTimestamp(row.getString("timestamp")));
    }

    private static LocalDateTime parseTimestamp(String value) {
        try {
            return LocalDateTime.parse(value);
        } catch (DateTimeParseException | NullPointerException e) {
            // A corrupt/legacy timestamp should not sink the whole load; fall back to
            // "now" so the message is still shown rather than dropped.
            return LocalDateTime.now();
        }
    }
}
