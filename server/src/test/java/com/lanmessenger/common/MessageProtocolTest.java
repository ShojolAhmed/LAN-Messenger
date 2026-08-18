package com.lanmessenger.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the wire {@link Message} protocol: encoding, decoding and the
 * small rules that make the format robust and extensible.
 */
class MessageProtocolTest {

    @Test
    @DisplayName("encode/decode is a lossless round-trip")
    void roundTrip() {
        Message original = Message.privateMessage("alice", "bob", "hi there");
        Message decoded = Message.decode(original.encode());
        assertEquals(original, decoded);
        assertEquals(MessageType.PRIVATE_MESSAGE, decoded.type());
        assertEquals("alice", decoded.sender());
        assertEquals("bob", decoded.recipient());
        assertEquals("hi there", decoded.content());
    }

    @Test
    @DisplayName("content may contain the '|' delimiter because it is the last field")
    void contentMayContainDelimiter() {
        Message decoded = Message.decode("GLOBAL_MESSAGE|alice||a|b|c");
        assertEquals(MessageType.GLOBAL_MESSAGE, decoded.type());
        assertEquals("alice", decoded.sender());
        assertEquals("a|b|c", decoded.content());
    }

    @Test
    @DisplayName("newlines survive a round-trip yet never split the wire line")
    void newlinesPreservedOnASingleLine() {
        Message original = Message.global("alice", "line1\nline2\r\nline3");
        String encoded = original.encode();
        assertEquals(1, encoded.lines().count(),
                "an encoded message must stay on exactly one physical line");
        Message decoded = Message.decode(encoded);
        assertEquals("line1\nline2\nline3", decoded.content(),
                "newlines are restored on decode (CR/CRLF normalised to LF)");
    }

    @Test
    @DisplayName("USER_LIST content encodes and parses back into usernames")
    void userListEntries() {
        Message list = Message.userList(List.of("alice", "bob", "carol"));
        assertEquals("alice,bob,carol", list.content());
        assertEquals(List.of("alice", "bob", "carol"), list.userListEntries());
        assertTrue(Message.userList(List.of()).userListEntries().isEmpty());
    }

    @Test
    @DisplayName("unknown message types are rejected")
    void unknownTypeRejected() {
        assertThrows(IllegalArgumentException.class, () -> Message.decode("NOT_A_TYPE|a|b|c"));
        assertThrows(IllegalArgumentException.class, () -> Message.decode(null));
    }

    @Test
    @DisplayName("null text fields are normalised to empty strings")
    void nullsBecomeEmpty() {
        Message message = new Message(MessageType.DISCONNECT, null, null, null);
        assertEquals("", message.sender());
        assertEquals("", message.recipient());
        assertEquals("", message.content());
        assertEquals("DISCONNECT|||", message.encode());
    }
}
