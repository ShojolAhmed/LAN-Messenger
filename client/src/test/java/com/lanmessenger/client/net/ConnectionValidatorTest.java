package com.lanmessenger.client.net;

import com.lanmessenger.client.net.ConnectionValidator.Field;
import com.lanmessenger.client.net.ConnectionValidator.Result;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link ConnectionValidator}, the JavaFX-free client-side check
 * that runs before any socket is opened. These cover the connection-screen error
 * cases the feature requires: an empty or malformed username, an invalid server
 * IP, and an out-of-range or non-numeric port &mdash; plus the happy path where
 * clean, trimmed values come back ready to connect.
 */
class ConnectionValidatorTest {

    private static final String VALID_USER = "Shojol";
    private static final String VALID_HOST = "192.168.0.100";
    private static final String VALID_PORT = "5000";

    // -------------------------------------------------------------------------
    // Success
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("valid input passes and returns cleaned values")
    void validInputPasses() {
        Result result = ConnectionValidator.validate(VALID_USER, VALID_HOST, VALID_PORT);

        assertTrue(result.valid(), "expected valid input to pass");
        assertNull(result.error());
        assertEquals(Field.NONE, result.field());
        assertEquals("Shojol", result.username());
        assertEquals("192.168.0.100", result.host());
        assertEquals(5000, result.port());
    }

    @Test
    @DisplayName("surrounding whitespace is trimmed from all fields")
    void trimsSurroundingWhitespace() {
        Result result = ConnectionValidator.validate("  Shojol  ", "  127.0.0.1  ", "  5000  ");

        assertTrue(result.valid());
        assertEquals("Shojol", result.username());
        assertEquals("127.0.0.1", result.host());
        assertEquals(5000, result.port());
    }

    @Test
    @DisplayName("usernames may contain letters, digits, '.', '_' and '-'")
    void allowsPermittedUsernameCharacters() {
        assertTrue(ConnectionValidator.validate("a.b_c-D9", VALID_HOST, VALID_PORT).valid());
    }

    @Test
    @DisplayName("host names and localhost are accepted, not only IPs")
    void acceptsHostNamesAndIps() {
        for (String host : new String[] {"127.0.0.1", "10.0.0.255", "192.168.1.1", "localhost", "my-server.local"}) {
            assertTrue(ConnectionValidator.isValidHost(host), host + " should be a valid host");
        }
    }

    @Test
    @DisplayName("port boundaries 1 and 65535 are accepted")
    void acceptsPortBoundaries() {
        assertTrue(ConnectionValidator.validate(VALID_USER, VALID_HOST, "1").valid());
        assertTrue(ConnectionValidator.validate(VALID_USER, VALID_HOST, "65535").valid());
    }

    // -------------------------------------------------------------------------
    // Invalid username
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("an empty or blank username is rejected")
    void rejectsEmptyUsername() {
        assertError(ConnectionValidator.validate("", VALID_HOST, VALID_PORT),
                "Username cannot be empty.", Field.USERNAME);
        assertError(ConnectionValidator.validate("   ", VALID_HOST, VALID_PORT),
                "Username cannot be empty.", Field.USERNAME);
        assertError(ConnectionValidator.validate(null, VALID_HOST, VALID_PORT),
                "Username cannot be empty.", Field.USERNAME);
    }

    @Test
    @DisplayName("a username with invalid characters is rejected")
    void rejectsInvalidUsernameCharacters() {
        for (String bad : new String[] {"bad name", "user!", "who@home", "a/b", "emoji\uD83D\uDE00"}) {
            assertError(ConnectionValidator.validate(bad, VALID_HOST, VALID_PORT),
                    "Username contains invalid characters.", Field.USERNAME);
        }
    }

    @Test
    @DisplayName("a too-long username is rejected with a length-specific message")
    void rejectsTooLongUsername() {
        String tooLong = "a".repeat(25); // MAX_LENGTH is 24
        assertError(ConnectionValidator.validate(tooLong, VALID_HOST, VALID_PORT),
                "Username is too long (max 24 characters).", Field.USERNAME);
    }

    // -------------------------------------------------------------------------
    // Invalid server IP
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("a malformed server IP is rejected")
    void rejectsInvalidHost() {
        for (String bad : new String[] {"", "   ", "999.999.999.999", "1.2.3", "256.0.0.1",
                "1.2.3.4.5", "192.168.0.", "has space", "bad_host"}) {
            assertError(ConnectionValidator.validate(VALID_USER, bad, VALID_PORT),
                    "Server IP is invalid.", Field.HOST);
        }
    }

    // -------------------------------------------------------------------------
    // Invalid port
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("a non-numeric, empty or out-of-range port is rejected")
    void rejectsInvalidPort() {
        for (String bad : new String[] {"", "abc", "0", "-1", "65536", "99999", "5000.5", "5 000"}) {
            assertError(ConnectionValidator.validate(VALID_USER, VALID_HOST, bad),
                    "Port must be between 1 and 65535.", Field.PORT);
        }
    }

    // -------------------------------------------------------------------------
    // Ordering
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("username is validated before host and port")
    void reportsFirstErrorFirst() {
        // All three invalid: the username error should be reported first.
        assertError(ConnectionValidator.validate("", "999.999.999.999", "0"),
                "Username cannot be empty.", Field.USERNAME);
        // Valid username, invalid host and port: the host error should win.
        assertError(ConnectionValidator.validate(VALID_USER, "999.999.999.999", "0"),
                "Server IP is invalid.", Field.HOST);
    }

    private static void assertError(Result result, String expectedMessage, Field expectedField) {
        assertFalse(result.valid(), "expected validation to fail");
        assertEquals(expectedMessage, result.error());
        assertEquals(expectedField, result.field());
        assertNull(result.username(), "failed result should not expose a username");
    }
}
