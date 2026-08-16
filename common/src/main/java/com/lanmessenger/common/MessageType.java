package com.lanmessenger.common;

/**
 * The set of message types understood by the LAN Messenger wire protocol.
 *
 * <p>Each constant maps 1:1 to the {@code TYPE} token that appears at the start
 * of every line on the wire (see {@link Message} for the framing rules). Keeping
 * the vocabulary in a single enum means the client and server can never disagree
 * about what a type is called, and adding a new capability later is as simple as
 * adding a new constant here.
 *
 * <p>Direction is only a convention (the protocol itself does not enforce it) but
 * documenting it keeps the design easy to reason about:
 * <ul>
 *   <li><b>Client &rarr; Server:</b> {@link #LOGIN}, {@link #GLOBAL_MESSAGE},
 *       {@link #PRIVATE_MESSAGE}, {@link #DISCONNECT} and {@link #USER_LIST}
 *       (as a request).</li>
 *   <li><b>Server &rarr; Client:</b> {@link #LOGIN_SUCCESS}, {@link #LOGIN_FAILED},
 *       {@link #USER_LIST} (as a reply), {@link #USER_JOINED}, {@link #USER_LEFT}
 *       and {@link #ERROR}. Global and private messages are also relayed onwards
 *       to recipients.</li>
 * </ul>
 */
public enum MessageType {

    /** Client asks to join the chat with a chosen username. */
    LOGIN,

    /** Server confirms that a login was accepted. */
    LOGIN_SUCCESS,

    /** Server rejects a login (e.g. the username is taken or invalid). */
    LOGIN_FAILED,

    /** A message addressed to every connected user. */
    GLOBAL_MESSAGE,

    /** A message addressed to a single named recipient. */
    PRIVATE_MESSAGE,

    /** The current list of online usernames (request from client, reply from server). */
    USER_LIST,

    /** Server notifies everyone that a new user has joined. */
    USER_JOINED,

    /** Server notifies everyone that a user has left. */
    USER_LEFT,

    /** Either side signals an intentional, graceful disconnect. */
    DISCONNECT,

    /** Server reports a protocol or application error to a client. */
    ERROR;

    /**
     * Resolves a wire token (the leading {@code TYPE} field of a line) to its
     * enum constant.
     *
     * @param token the raw token; leading/trailing whitespace and case are ignored
     * @return the matching {@code MessageType}
     * @throws IllegalArgumentException if {@code token} is {@code null} or unknown
     */
    public static MessageType fromWire(String token) {
        if (token == null) {
            throw new IllegalArgumentException("Message type token is null");
        }
        try {
            return MessageType.valueOf(token.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Unknown message type: '" + token + "'");
        }
    }
}
