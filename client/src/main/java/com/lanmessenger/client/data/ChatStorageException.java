package com.lanmessenger.client.data;

/**
 * Unchecked exception for failures in the persistence layer (opening the database,
 * running a statement, mapping a row, ...).
 *
 * <p>It wraps the underlying {@link java.sql.SQLException} (or I/O error) so the
 * rest of the data layer exposes a small, JDBC-free surface. Callers that must stay
 * running when storage is unavailable &mdash; the UI &mdash; catch this at a single
 * boundary (see {@code client.history}) and simply carry on without persistence.
 */
public class ChatStorageException extends RuntimeException {

    public ChatStorageException(String message, Throwable cause) {
        super(message, cause);
    }

    public ChatStorageException(String message) {
        super(message);
    }
}
