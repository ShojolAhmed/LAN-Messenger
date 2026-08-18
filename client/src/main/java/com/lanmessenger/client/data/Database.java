package com.lanmessenger.client.data;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Owns the JDBC {@link Connection} to the SQLite chat database and creates the
 * schema on first use. This is the only class in the project that speaks to JDBC's
 * connection API; the DAOs borrow the open connection through {@link #connection()}.
 *
 * <p><b>Threading.</b> A single {@code Connection} is not thread-safe. Callers must
 * confine all use of a {@code Database} (and its DAOs) to one thread. The client
 * does this by driving every database call through a single background executor
 * (see {@code client.history.PersistentChatHistory}).
 *
 * <p><b>Errors.</b> {@link #open(Path)} throws {@link ChatStorageException} if the
 * database cannot be opened or the schema cannot be created; the caller is expected
 * to degrade gracefully rather than crash.
 */
public final class Database implements AutoCloseable {

    private static final String CREATE_USERS = """
            CREATE TABLE IF NOT EXISTS users (
                username   TEXT PRIMARY KEY,
                first_seen TEXT NOT NULL,
                last_seen  TEXT NOT NULL
            )
            """;

    private static final String CREATE_MESSAGES = """
            CREATE TABLE IF NOT EXISTS messages (
                id        INTEGER PRIMARY KEY AUTOINCREMENT,
                owner     TEXT NOT NULL,
                sender    TEXT NOT NULL,
                recipient TEXT NOT NULL,
                content   TEXT NOT NULL,
                type      TEXT NOT NULL,
                timestamp TEXT NOT NULL
            )
            """;

    // Indexes matching the two read patterns: a user's global feed, and a user's
    // one-to-one thread with a given peer. Both read newest-last, ordered by time.
    private static final String INDEX_GLOBAL =
            "CREATE INDEX IF NOT EXISTS idx_messages_global "
                    + "ON messages(owner, type, timestamp, id)";
    private static final String INDEX_DIRECT =
            "CREATE INDEX IF NOT EXISTS idx_messages_direct "
                    + "ON messages(owner, type, sender, recipient, timestamp, id)";

    private final Connection connection;

    private Database(Connection connection) {
        this.connection = connection;
    }

    /**
     * Opens (creating if necessary) the SQLite database at {@code file}, ensuring
     * its parent directory exists and the schema is present.
     *
     * @throws ChatStorageException if the database cannot be opened or initialised
     */
    public static Database open(Path file) {
        try {
            Path parent = file.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
        } catch (IOException e) {
            throw new ChatStorageException("Could not create data directory for " + file, e);
        }

        // Not strictly required on modern JDKs (the driver self-registers via the
        // service loader), but harmless and explicit about the dependency.
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException ignored) {
            // Fall through: DriverManager will still find a registered driver.
        }

        String url = "jdbc:sqlite:" + file.toAbsolutePath();
        Connection connection;
        try {
            connection = DriverManager.getConnection(url);
        } catch (SQLException e) {
            throw new ChatStorageException("Could not open SQLite database at " + file, e);
        }

        try {
            Database database = new Database(connection);
            database.initialise();
            return database;
        } catch (SQLException e) {
            closeQuietly(connection);
            throw new ChatStorageException("Could not initialise database schema at " + file, e);
        }
    }

    private void initialise() throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA busy_timeout = 5000");
            statement.execute("PRAGMA foreign_keys = ON");
            statement.execute(CREATE_USERS);
            statement.execute(CREATE_MESSAGES);
            statement.execute(INDEX_GLOBAL);
            statement.execute(INDEX_DIRECT);
        }
    }

    /** The open connection, borrowed by the DAOs in this package. */
    Connection connection() {
        return connection;
    }

    @Override
    public void close() {
        closeQuietly(connection);
    }

    private static void closeQuietly(Connection connection) {
        try {
            if (connection != null && !connection.isClosed()) {
                connection.close();
            }
        } catch (SQLException ignored) {
            // Closing is best-effort; nothing useful to do on failure.
        }
    }
}
