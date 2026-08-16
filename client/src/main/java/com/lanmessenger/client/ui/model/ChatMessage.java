package com.lanmessenger.client.ui.model;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * An immutable view-model for a single message rendered in the chat pane.
 *
 * <p>The {@link Kind} determines how a message is drawn (see the {@code .bubble-*}
 * rules in {@code theme.css}):
 * <ul>
 *   <li>{@link Kind#INCOMING} &mdash; from another user; left-aligned surface bubble;</li>
 *   <li>{@link Kind#OUTGOING} &mdash; sent by us; right-aligned accent bubble;</li>
 *   <li>{@link Kind#SYSTEM} &mdash; a quiet, centered notice (joins, leaves, info).</li>
 * </ul>
 *
 * <p>This type is intentionally free of the wire {@link com.lanmessenger.common.Message}
 * so the UI can be built and demonstrated with sample data; a later phase maps
 * incoming protocol messages onto these.
 *
 * @param kind      how the message should be presented
 * @param author    the sender's display name ({@code SYSTEM} messages may omit it)
 * @param content   the message text
 * @param timestamp when the message was created
 */
public record ChatMessage(Kind kind, String author, String content, LocalDateTime timestamp) {

    /** How a message is presented in the list. */
    public enum Kind { INCOMING, OUTGOING, SYSTEM }

    public ChatMessage {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(content, "content");
        Objects.requireNonNull(timestamp, "timestamp");
        author = author == null ? "" : author;
    }

    /** An incoming message from {@code author}. */
    public static ChatMessage incoming(String author, String content, LocalDateTime at) {
        return new ChatMessage(Kind.INCOMING, author, content, at);
    }

    /** A message we sent. */
    public static ChatMessage outgoing(String content, LocalDateTime at) {
        return new ChatMessage(Kind.OUTGOING, "You", content, at);
    }

    /** A centered system/info notice. */
    public static ChatMessage system(String content, LocalDateTime at) {
        return new ChatMessage(Kind.SYSTEM, "", content, at);
    }

    public boolean isSystem() {
        return kind == Kind.SYSTEM;
    }

    public boolean isOutgoing() {
        return kind == Kind.OUTGOING;
    }
}
