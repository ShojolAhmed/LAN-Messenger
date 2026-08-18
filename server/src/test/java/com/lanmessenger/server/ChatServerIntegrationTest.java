package com.lanmessenger.server;

import com.lanmessenger.common.Message;
import com.lanmessenger.common.MessageType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Integration tests that exercise the {@link ChatServer} over real TCP sockets on
 * the loopback interface. They verify the phase goals:
 * <ul>
 *   <li>the server starts and binds a port,</li>
 *   <li>multiple clients can connect and log in concurrently,</li>
 *   <li>messages are routed (broadcast and private), and</li>
 *   <li>a client disconnecting &mdash; even abruptly &mdash; does not crash the
 *       server or disturb the others.</li>
 * </ul>
 *
 * <p>Each test starts a fresh server on an OS-assigned free port (port {@code 0}),
 * so tests never collide with a running instance or each other.
 */
@Timeout(15)
class ChatServerIntegrationTest {

    private ChatServer server;
    private int port;

    @BeforeEach
    void startServer() throws IOException {
        server = new ChatServer(new ServerConfiguration(0));
        server.start();
        port = server.getBoundPort();
    }

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.shutdown();
        }
    }

    @Test
    @DisplayName("server starts and reports a bound port")
    void serverStarts() {
        assertTrue(server.isRunning(), "server should be running after start()");
        assertTrue(port > 0, "server should report a positive bound port");
    }

    @Test
    @DisplayName("multiple clients can connect and log in")
    void multipleClientsCanConnectAndLogin() throws IOException {
        try (TestClient alice = new TestClient(port);
             TestClient bob = new TestClient(port);
             TestClient carol = new TestClient(port)) {

            alice.login("alice");
            bob.login("bob");
            carol.login("carol");

            // Each client is greeted with LOGIN_SUCCESS then the current USER_LIST.
            for (TestClient client : List.of(alice, bob, carol)) {
                client.awaitType(MessageType.LOGIN_SUCCESS);
                client.awaitType(MessageType.USER_LIST);
            }

            assertEquals(3, server.clientManager().size(), "all three clients should be registered");
            assertEquals(List.of("alice", "bob", "carol"), server.clientManager().usernames());
        }
    }

    @Test
    @DisplayName("a global message is delivered to the other clients")
    void globalMessageIsBroadcast() throws IOException {
        try (TestClient alice = new TestClient(port);
             TestClient bob = new TestClient(port);
             TestClient carol = new TestClient(port)) {

            alice.loginAndSettle("alice");
            bob.loginAndSettle("bob");
            carol.loginAndSettle("carol");

            alice.drainPending();
            bob.drainPending();
            carol.drainPending();

            alice.send(Message.global("alice", "hello everyone"));

            Message toBob = bob.awaitType(MessageType.GLOBAL_MESSAGE);
            Message toCarol = carol.awaitType(MessageType.GLOBAL_MESSAGE);

            assertEquals("alice", toBob.sender());
            assertEquals("hello everyone", toBob.content());
            assertEquals("alice", toCarol.sender());
            assertEquals("hello everyone", toCarol.content());
        }
    }

    @Test
    @DisplayName("a private message reaches only its recipient")
    void privateMessageGoesOnlyToRecipient() throws IOException {
        try (TestClient alice = new TestClient(port);
             TestClient bob = new TestClient(port);
             TestClient carol = new TestClient(port)) {

            alice.loginAndSettle("alice");
            bob.loginAndSettle("bob");
            carol.loginAndSettle("carol");

            alice.drainPending();
            bob.drainPending();
            carol.drainPending();

            alice.send(Message.privateMessage("alice", "bob", "secret"));
            // A marker broadcast lets us prove Carol did NOT receive the private note:
            // her very next message must be the marker, not the secret.
            alice.send(Message.global("alice", "marker"));

            Message bobFirst = bob.readNext();
            assertEquals(MessageType.PRIVATE_MESSAGE, bobFirst.type());
            assertEquals("alice", bobFirst.sender());
            assertEquals("secret", bobFirst.content());

            Message carolFirst = carol.readNext();
            assertEquals(MessageType.GLOBAL_MESSAGE, carolFirst.type(),
                    "Carol must not receive a private message addressed to Bob");
            assertEquals("marker", carolFirst.content());
        }
    }

    @Test
    @DisplayName("a private message to an unknown user is reported to the sender with the recipient tagged")
    void privateMessageToUnknownUserIsReported() throws IOException {
        try (TestClient alice = new TestClient(port)) {
            alice.loginAndSettle("alice");

            alice.send(Message.privateMessage("alice", "ghost", "anyone there?"));

            Message error = alice.awaitType(MessageType.ERROR);
            assertEquals("ghost", error.recipient(),
                    "the delivery error must carry the intended recipient so the client can route it");
            assertTrue(error.content().toLowerCase().contains("not online"));
        }
    }

    @Test
    @DisplayName("a private message addressed to yourself is rejected")
    void privateMessageToSelfIsRejected() throws IOException {
        try (TestClient alice = new TestClient(port)) {
            alice.loginAndSettle("alice");

            alice.send(Message.privateMessage("alice", "alice", "note to self"));

            Message error = alice.awaitType(MessageType.ERROR);
            assertEquals("alice", error.recipient(), "the error should identify the intended recipient");
            assertTrue(error.content().toLowerCase().contains("yourself"));
        }
    }

    @Test
    @DisplayName("a duplicate username is rejected but the name is free again for a retry")
    void duplicateUsernameIsRejected() throws IOException {
        try (TestClient first = new TestClient(port);
             TestClient second = new TestClient(port)) {

            first.loginAndSettle("sam");

            second.login("sam");
            Message failure = second.awaitType(MessageType.LOGIN_FAILED);
            assertTrue(failure.content().toLowerCase().contains("taken"));

            // The same connection may retry with a different name.
            second.login("sam2");
            second.awaitType(MessageType.LOGIN_SUCCESS);
            assertEquals(2, server.clientManager().size());
        }
    }

    @Test
    @DisplayName("messages must be preceded by a successful login")
    void messagesBeforeLoginAreRejected() throws IOException {
        try (TestClient client = new TestClient(port)) {
            client.send(Message.global("nobody", "too soon"));
            Message error = client.awaitType(MessageType.ERROR);
            assertTrue(error.content().toLowerCase().contains("login"));
        }
    }

    @Test
    @DisplayName("a graceful DISCONNECT notifies the remaining clients")
    void gracefulDisconnectNotifiesOthers() throws IOException {
        try (TestClient alice = new TestClient(port);
             TestClient bob = new TestClient(port)) {

            alice.loginAndSettle("alice");
            bob.loginAndSettle("bob");
            alice.drainPending();

            bob.send(Message.disconnect());

            Message left = alice.awaitType(MessageType.USER_LEFT);
            assertEquals("bob", left.sender());
            waitForClientCount(1);
        }
    }

    @Test
    @DisplayName("an abrupt disconnect (RST) does not crash the server")
    void abruptDisconnectDoesNotCrashServer() throws IOException {
        try (TestClient alice = new TestClient(port)) {
            alice.loginAndSettle("alice");

            // Bob connects, logs in, then vanishes without a clean close (TCP RST).
            TestClient bob = new TestClient(port);
            bob.loginAndSettle("bob");
            waitForClientCount(2);
            bob.killAbruptly();

            // The server should notice, tidy up, and tell Alice — without dying.
            Message left = alice.awaitType(MessageType.USER_LEFT);
            assertEquals("bob", left.sender());

            assertTrue(server.isRunning(), "server must stay up after a client crash");
            waitForClientCount(1);

            // And Alice is still fully functional afterwards: a USER_LIST request
            // is answered and no longer lists the crashed client.
            alice.send(new Message(MessageType.USER_LIST, "", "", ""));
            Message roster = alice.awaitType(MessageType.USER_LIST);
            assertTrue(roster.userListEntries().contains("alice"));
            assertFalse(roster.userListEntries().contains("bob"));
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private void waitForClientCount(int expected) {
        long deadline = System.currentTimeMillis() + 3_000;
        while (System.currentTimeMillis() < deadline) {
            if (server.clientManager().size() == expected) {
                return;
            }
            sleep(20);
        }
        assertEquals(expected, server.clientManager().size(), "unexpected number of connected clients");
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * A minimal blocking TCP test client. It speaks the same line protocol as the
     * real client will, so these tests double as living documentation of the
     * protocol handshake.
     */
    private static final class TestClient implements AutoCloseable {

        private static final int READ_POLL_MILLIS = 500;
        private static final int DEFAULT_WAIT_MILLIS = 5_000;

        private final Socket socket;
        private final BufferedReader in;
        private final PrintWriter out;

        TestClient(int port) throws IOException {
            this.socket = new Socket("127.0.0.1", port);
            this.socket.setSoTimeout(READ_POLL_MILLIS);
            this.in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            this.out = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true);
        }

        void send(Message message) {
            out.println(message.encode());
        }

        void login(String username) {
            send(Message.login(username));
        }

        /** Logs in and consumes the LOGIN_SUCCESS + USER_LIST greeting. */
        void loginAndSettle(String username) {
            login(username);
            awaitType(MessageType.LOGIN_SUCCESS);
            awaitType(MessageType.USER_LIST);
        }

        /** Reads the next message, waiting up to the default timeout. */
        Message readNext() {
            return receive(DEFAULT_WAIT_MILLIS);
        }

        /** Reads (skipping other messages) until one of {@code type} arrives. */
        Message awaitType(MessageType type) {
            long deadline = System.currentTimeMillis() + DEFAULT_WAIT_MILLIS;
            List<MessageType> seen = new ArrayList<>();
            while (System.currentTimeMillis() < deadline) {
                Message message = receiveOrNull(deadline - System.currentTimeMillis());
                if (message == null) {
                    continue;
                }
                if (message.type() == type) {
                    return message;
                }
                seen.add(message.type());
            }
            fail("Timed out waiting for " + type + "; saw " + seen);
            return null; // unreachable
        }

        /** Discards any already-queued messages (e.g. USER_JOINED notifications). */
        void drainPending() {
            try {
                socket.setSoTimeout(200);
                while (true) {
                    String line = in.readLine();
                    if (line == null) {
                        return;
                    }
                }
            } catch (SocketTimeoutException expected) {
                // Nothing more buffered — done draining.
            } catch (IOException ex) {
                // Connection gone; nothing to drain.
            } finally {
                try {
                    socket.setSoTimeout(READ_POLL_MILLIS);
                } catch (IOException ignored) {
                    // best effort
                }
            }
        }

        /** Simulates a crashed client: SO_LINGER 0 forces a TCP RST on close. */
        void killAbruptly() throws IOException {
            socket.setSoLinger(true, 0);
            socket.close();
        }

        private Message receive(long timeoutMillis) {
            Message message = receiveWithin(timeoutMillis);
            if (message == null) {
                fail("Timed out waiting for a message");
            }
            return message;
        }

        private Message receiveOrNull(long timeoutMillis) {
            return receiveWithin(Math.max(1, timeoutMillis));
        }

        private Message receiveWithin(long timeoutMillis) {
            long deadline = System.currentTimeMillis() + timeoutMillis;
            while (System.currentTimeMillis() < deadline) {
                try {
                    String line = in.readLine();
                    if (line == null) {
                        fail("Server closed the connection unexpectedly");
                    }
                    return Message.decode(line);
                } catch (SocketTimeoutException retry) {
                    // Poll again until the deadline.
                } catch (IOException ex) {
                    fail("I/O error while reading: " + ex.getMessage());
                }
            }
            return null;
        }

        @Override
        public void close() {
            try {
                socket.close();
            } catch (IOException ignored) {
                // best effort
            }
        }
    }
}
