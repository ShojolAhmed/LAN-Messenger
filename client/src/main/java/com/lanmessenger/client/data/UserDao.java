package com.lanmessenger.client.data;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Data-access object for the {@code users} table: a small registry of every
 * username this client has seen (itself, peers it has messaged, and the authors of
 * stored messages), with the first and last time each was seen.
 *
 * <p>{@link #markSeen(String, LocalDateTime)} is an idempotent upsert, so recording
 * the same user repeatedly simply refreshes {@code last_seen}.
 */
public final class UserDao {

    private static final String UPSERT =
            "INSERT INTO users(username, first_seen, last_seen) VALUES(?, ?, ?) "
                    + "ON CONFLICT(username) DO UPDATE SET last_seen = excluded.last_seen";

    private static final String SELECT_ALL =
            "SELECT username FROM users ORDER BY username ASC";

    private final Database database;

    public UserDao(Database database) {
        this.database = Objects.requireNonNull(database, "database");
    }

    /** Records {@code username} as seen at {@code when}; blank names are ignored. */
    public void markSeen(String username, LocalDateTime when) {
        if (username == null || username.isBlank()) {
            return;
        }
        String at = when.toString();
        try (PreparedStatement statement = database.connection().prepareStatement(UPSERT)) {
            statement.setString(1, username);
            statement.setString(2, at);
            statement.setString(3, at);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new ChatStorageException("Could not record user " + username, e);
        }
    }

    /** All known usernames, alphabetically. */
    public List<String> findUsernames() {
        try (PreparedStatement statement = database.connection().prepareStatement(SELECT_ALL);
             ResultSet rows = statement.executeQuery()) {
            List<String> names = new ArrayList<>();
            while (rows.next()) {
                names.add(rows.getString("username"));
            }
            return names;
        } catch (SQLException e) {
            throw new ChatStorageException("Could not load users", e);
        }
    }
}
