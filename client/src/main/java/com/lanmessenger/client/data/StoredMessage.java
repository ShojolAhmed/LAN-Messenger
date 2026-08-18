package com.lanmessenger.client.data;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * A single persisted chat message &mdash; one row of the {@code messages} table.
 *
 * <p>This is the data layer's own model, deliberately independent of the wire
 * {@link com.lanmessenger.common.Message} and of the UI's {@code ChatMessage}, so
 * the persistence code has no dependency on networking or JavaFX.
 *
 * <p>Fields:
 * <ul>
 *   <li>{@code id} &mdash; auto-assigned primary key ({@code 0} before insertion);</li>
 *   <li>{@code owner} &mdash; the local account this history belongs to, so several
 *       users on one machine keep separate private histories;</li>
 *   <li>{@code sender} / {@code recipient} &mdash; usernames; {@code recipient} is
 *       empty for a global message;</li>
 *   <li>{@code content} &mdash; the message text;</li>
 *   <li>{@code type} &mdash; the {@link com.lanmessenger.common.MessageType} name,
 *       i.e. {@code GLOBAL_MESSAGE} or {@code PRIVATE_MESSAGE};</li>
 *   <li>{@code timestamp} &mdash; when the message was created/received.</li>
 * </ul>
 */
public record StoredMessage(
        long id,
        String owner,
        String sender,
        String recipient,
        String content,
        String type,
        LocalDateTime timestamp) {

    public StoredMessage {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(sender, "sender");
        Objects.requireNonNull(recipient, "recipient");
        Objects.requireNonNull(content, "content");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(timestamp, "timestamp");
    }

    /**
     * Creates a row to be inserted (its {@code id} is assigned by the database).
     */
    public static StoredMessage create(String owner, String sender, String recipient,
                                       String content, String type, LocalDateTime timestamp) {
        return new StoredMessage(0L, owner, sender, recipient, content, type, timestamp);
    }
}
