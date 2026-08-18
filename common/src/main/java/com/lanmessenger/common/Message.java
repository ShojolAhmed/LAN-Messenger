package com.lanmessenger.common;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * An immutable value object representing one protocol message, plus the rules
 * for turning it into and out of its on-the-wire form.
 *
 * <h2>Wire format</h2>
 * Every message is a single line of UTF-8 text (terminated by {@code '\n'}) made
 * up of four pipe-separated fields:
 *
 * <pre>{@code   TYPE | sender | recipient | content}</pre>
 *
 * <ul>
 *   <li><b>TYPE</b> &mdash; one of {@link MessageType}.</li>
 *   <li><b>sender</b> &mdash; who sent it (may be empty for server-originated messages).</li>
 *   <li><b>recipient</b> &mdash; the target user for a {@link MessageType#PRIVATE_MESSAGE}
 *       (empty otherwise).</li>
 *   <li><b>content</b> &mdash; free-form payload; always the last field.</li>
 * </ul>
 *
 * <p><b>Why this design?</b> A line-based text protocol is trivial to read on the
 * wire (you can even test it with {@code telnet}), trivial to parse
 * ({@link String#split(String, int)}), and trivially extensible (add a new
 * {@link MessageType} without breaking existing parsing). Because {@code content}
 * is always the final field it is parsed with a limit of {@code 4}, so the payload
 * itself may safely contain the {@code '|'} delimiter. Only a literal line break
 * would break the single-line framing, so {@link #encode()} represents newlines
 * inside {@code content} with a separator that {@link java.io.BufferedReader#readLine()}
 * does not treat as a line terminator; {@link #decode(String)} restores them. This
 * keeps every message on exactly one physical line while still carrying multi-line
 * text (for example a message composed with Shift+Enter) end to end.
 */
public final class Message {

    /** Field delimiter used on the wire. */
    public static final char DELIMITER = '|';

    /** Separator used inside the {@code content} of a {@link MessageType#USER_LIST}. */
    public static final String USER_LIST_SEPARATOR = ",";

    private static final String DELIMITER_REGEX = "\\|";

    /**
     * On-the-wire stand-in for a newline inside {@code content}. U+2028 (Unicode
     * LINE SEPARATOR) is used because {@link java.io.BufferedReader#readLine()}
     * does <em>not</em> treat it as a line terminator, so an encoded message can
     * never be split across two reads even when it carries multi-line text. It is
     * converted back to a real {@code '\n'} on {@link #decode(String)}.
     */
    private static final char WIRE_NEWLINE = '\u2028';

    private final MessageType type;
    private final String sender;
    private final String recipient;
    private final String content;

    /**
     * Creates a message. {@code null} text fields are normalised to empty strings
     * so callers never have to worry about nulls on the wire.
     *
     * @param type      the message type (required)
     * @param sender    the sender username, or {@code null}/empty if not applicable
     * @param recipient the recipient username, or {@code null}/empty if not applicable
     * @param content   the payload, or {@code null}/empty if not applicable
     */
    public Message(MessageType type, String sender, String recipient, String content) {
        this.type = Objects.requireNonNull(type, "type");
        this.sender = nullToEmpty(sender);
        this.recipient = nullToEmpty(recipient);
        this.content = nullToEmpty(content);
    }

    // ---------------------------------------------------------------------
    // Factory helpers — these keep call sites readable and consistent.
    // ---------------------------------------------------------------------

    /** Client &rarr; server: request to join as {@code username}. */
    public static Message login(String username) {
        return new Message(MessageType.LOGIN, username, "", "");
    }

    /** Server &rarr; client: login accepted, with a human-readable welcome note. */
    public static Message loginSuccess(String info) {
        return new Message(MessageType.LOGIN_SUCCESS, "", "", info);
    }

    /** Server &rarr; client: login rejected, with the reason. */
    public static Message loginFailed(String reason) {
        return new Message(MessageType.LOGIN_FAILED, "", "", reason);
    }

    /** A broadcast chat message from {@code sender}. */
    public static Message global(String sender, String content) {
        return new Message(MessageType.GLOBAL_MESSAGE, sender, "", content);
    }

    /** A directed chat message from {@code sender} to {@code recipient}. */
    public static Message privateMessage(String sender, String recipient, String content) {
        return new Message(MessageType.PRIVATE_MESSAGE, sender, recipient, content);
    }

    /** Server &rarr; client: the current roster of online usernames. */
    public static Message userList(Collection<String> usernames) {
        return new Message(MessageType.USER_LIST, "", "", String.join(USER_LIST_SEPARATOR, usernames));
    }

    /** Server &rarr; clients: {@code username} just joined. */
    public static Message userJoined(String username) {
        return new Message(MessageType.USER_JOINED, username, "", "");
    }

    /** Server &rarr; clients: {@code username} just left. */
    public static Message userLeft(String username) {
        return new Message(MessageType.USER_LEFT, username, "", "");
    }

    /** Either side: request a graceful disconnect. */
    public static Message disconnect() {
        return new Message(MessageType.DISCONNECT, "", "", "");
    }

    /** Server &rarr; client: report a protocol/application error. */
    public static Message error(String detail) {
        return new Message(MessageType.ERROR, "", "", detail);
    }

    // ---------------------------------------------------------------------
    // Wire encoding / decoding
    // ---------------------------------------------------------------------

    /**
     * Serialises this message to its single-line wire form (without the trailing
     * newline; the transport adds that). Any newlines embedded in {@code content}
     * are represented by {@link #WIRE_NEWLINE} so a message can never span more
     * than one physical line; {@link #decode(String)} restores them.
     *
     * @return the encoded line
     */
    public String encode() {
        return type.name() + DELIMITER
                + sender + DELIMITER
                + recipient + DELIMITER
                + encodeNewlines(content);
    }

    /**
     * Parses one wire line back into a {@code Message}.
     *
     * @param line a single line as produced by {@link #encode()}
     * @return the decoded message
     * @throws IllegalArgumentException if {@code line} is {@code null} or its type
     *                                  token is missing/unknown
     */
    public static Message decode(String line) {
        if (line == null) {
            throw new IllegalArgumentException("Cannot decode a null line");
        }
        // Limit of 4 keeps everything after the third delimiter as the content,
        // so payloads may legally contain '|'.
        String[] parts = line.split(DELIMITER_REGEX, 4);
        MessageType type = MessageType.fromWire(parts[0]);
        String sender = parts.length > 1 ? parts[1].trim() : "";
        String recipient = parts.length > 2 ? parts[2].trim() : "";
        String content = parts.length > 3 ? decodeNewlines(parts[3]) : "";
        return new Message(type, sender, recipient, content);
    }

    /**
     * Convenience accessor that splits a {@link MessageType#USER_LIST} payload
     * into individual usernames.
     *
     * @return the usernames carried by this message (empty if none)
     */
    public List<String> userListEntries() {
        List<String> names = new ArrayList<>();
        if (!content.isEmpty()) {
            for (String name : content.split(USER_LIST_SEPARATOR)) {
                String trimmed = name.trim();
                if (!trimmed.isEmpty()) {
                    names.add(trimmed);
                }
            }
        }
        return names;
    }

    // ---------------------------------------------------------------------
    // Accessors
    // ---------------------------------------------------------------------

    public MessageType type() {
        return type;
    }

    public String sender() {
        return sender;
    }

    public String recipient() {
        return recipient;
    }

    public String content() {
        return content;
    }

    // ---------------------------------------------------------------------
    // Object contract
    // ---------------------------------------------------------------------

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Message other)) {
            return false;
        }
        return type == other.type
                && sender.equals(other.sender)
                && recipient.equals(other.recipient)
                && content.equals(other.content);
    }

    @Override
    public int hashCode() {
        return Objects.hash(type, sender, recipient, content);
    }

    @Override
    public String toString() {
        return "Message[" + encode() + "]";
    }

    // ---------------------------------------------------------------------
    // Internals
    // ---------------------------------------------------------------------

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    /**
     * Replaces every newline in {@code value} with {@link #WIRE_NEWLINE} so the
     * encoded message stays on a single physical line. CR and CRLF are normalised
     * to LF first, so the wire form is independent of the sender's platform.
     */
    private static String encodeNewlines(String value) {
        if (value.indexOf('\n') < 0 && value.indexOf('\r') < 0) {
            return value; // common case: nothing to escape
        }
        return value
                .replace("\r\n", "\n")
                .replace('\r', '\n')
                .replace('\n', WIRE_NEWLINE);
    }

    /** Restores real {@code '\n'} characters from their {@link #WIRE_NEWLINE} form. */
    private static String decodeNewlines(String value) {
        return value.indexOf(WIRE_NEWLINE) < 0 ? value : value.replace(WIRE_NEWLINE, '\n');
    }
}
