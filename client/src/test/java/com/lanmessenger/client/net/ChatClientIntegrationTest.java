package com.lanmessenger.client.net;

import com.lanmessenger.common.Message;
import com.lanmessenger.common.MessageType;
import com.lanmessenger.common.Protocol;
import com.lanmessenger.server.ChatServer;
import com.lanmessenger.server.ServerConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Integration tests for the client networking layer, exercised end-to-end against
 * the real {@link ChatServer} over loopback TCP. They cover the phase goals:
 * <ul>
 *   <li>a client connects and completes the login handshake,</li>
 *   <li>a client can send data and another client receives it,</li>
 *   <li>the server handles several clients at once, and</li>
 *   <li>disconnects &mdash; local, server-initiated, and connect failures &mdash;
 *       are handled safely, notifying the listener exactly once.</li>
 * </ul>
 *
 * <p>Because {@link ChatClientListener} is UI-agnostic, these tests run headless
 * with no JavaFX toolkit: a simple thread-safe {@link RecordingListener} captures
 * the callbacks that fire on the client's background threads.
 *
 * <p>Each test uses a fresh server on an OS-assigned free port (port {@code 0}).
 */
@Timeout(20)
class ChatClientIntegrationTest {

    private static final long AWAIT_MILLIS = 5_000;

    private ChatServer server;
    private int port;
    private final List<ChatClient> clients = new ArrayList<>();

    @BeforeEach
    void startServer() throws IOException {
        server = new ChatServer(new ServerConfiguration(0));
        server.start();
        port = server.getBoundPort();
    }

    @AfterEach
    void tearDown() {
        for (ChatClient client : clients) {
            client.disconnect();
        }
        if (server != null) {
            server.shutdown();
        }
    }

    @Test
    @DisplayName("client connects and completes the login handshake")
    void clientConnectsAndLogsIn() throws Exception {
        RecordingListener alice = new RecordingListener();
        ChatClient client = connectAndSettle(alice, "alice");

        assertTrue(client.isConnected(), "client should report a live connection");
        assertEquals("alice", client.username());
        waitUntil(() -> server.clientManager().size() == 1);
        assertTrue(server.clientManager().usernames().contains("alice"));
    }

    @Test
    @DisplayName("a duplicate username is rejected with LOGIN_FAILED")
    void duplicateUsernameRejectedAtLogin() throws Exception {
        RecordingListener alice = new RecordingListener();
        connectAndSettle(alice, "alice");

        // A second client requests the same name. The transport connection succeeds,
        // but the server's login verdict is a clean rejection the UI can surface.
        RecordingListener duplicate = new RecordingListener();
        ChatClient duplicateClient = new ChatClient(duplicate);
        clients.add(duplicateClient);
        duplicateClient.connect("127.0.0.1", port, "alice");

        assertTrue(duplicate.connected.await(AWAIT_MILLIS, TimeUnit.MILLISECONDS),
                "the transport connection should succeed before the login verdict");
        Message failed = duplicate.awaitType(MessageType.LOGIN_FAILED);
        assertTrue(failed.content().toLowerCase().contains("taken"),
                "the rejection should explain the name is already taken");

        // Only the original 'alice' remains registered on the server.
        waitUntil(() -> server.clientManager().size() == 1);
        assertTrue(server.clientManager().usernames().contains("alice"));
    }

    @Test
    @DisplayName("a client can send data and a peer receives it")
    void clientCanSendAndPeerReceives() throws Exception {
        RecordingListener alice = new RecordingListener();
        RecordingListener bob = new RecordingListener();
        ChatClient aliceClient = connectAndSettle(alice, "alice");
        connectAndSettle(bob, "bob");

        aliceClient.sendGlobalMessage("hello everyone");

        Message received = bob.awaitType(MessageType.GLOBAL_MESSAGE);
        assertEquals("alice", received.sender());
        assertEquals("hello everyone", received.content());
    }

    @Test
    @DisplayName("a private message reaches only its recipient")
    void privateMessageReachesOnlyRecipient() throws Exception {
        RecordingListener alice = new RecordingListener();
        RecordingListener bob = new RecordingListener();
        RecordingListener carol = new RecordingListener();
        ChatClient aliceClient = connectAndSettle(alice, "alice");
        connectAndSettle(bob, "bob");
        connectAndSettle(carol, "carol");

        aliceClient.sendPrivateMessage("bob", "secret");
        // A marker broadcast proves Carol never sees the private note: her next
        // GLOBAL_MESSAGE must be the marker, with no PRIVATE_MESSAGE before it.
        aliceClient.sendGlobalMessage("marker");

        Message toBob = bob.awaitType(MessageType.PRIVATE_MESSAGE);
        assertEquals("alice", toBob.sender());
        assertEquals("secret", toBob.content());

        Message toCarol;
        do {
            toCarol = carol.poll(AWAIT_MILLIS);
            assertNotNull(toCarol, "Carol should receive the marker broadcast");
            assertNotEquals(MessageType.PRIVATE_MESSAGE, toCarol.type(),
                    "Carol must never receive a private message addressed to Bob");
        } while (toCarol.type() != MessageType.GLOBAL_MESSAGE);
        assertEquals("marker", toCarol.content());
    }

    @Test
    @DisplayName("a global message is delivered to every other client")
    void globalMessageReachesAllOtherClients() throws Exception {
        RecordingListener alice = new RecordingListener();
        RecordingListener bob = new RecordingListener();
        RecordingListener carol = new RecordingListener();
        ChatClient aliceClient = connectAndSettle(alice, "alice");
        connectAndSettle(bob, "bob");
        connectAndSettle(carol, "carol");

        aliceClient.sendGlobalMessage("hello everyone!");

        for (RecordingListener peer : List.of(bob, carol)) {
            Message received = peer.awaitType(MessageType.GLOBAL_MESSAGE);
            assertEquals("alice", received.sender(), "the server stamps the authenticated sender");
            assertEquals("hello everyone!", received.content());
        }
    }

    @Test
    @DisplayName("an empty global message is not broadcast to peers")
    void emptyGlobalMessageIsDropped() throws Exception {
        RecordingListener alice = new RecordingListener();
        RecordingListener bob = new RecordingListener();
        ChatClient aliceClient = connectAndSettle(alice, "alice");
        connectAndSettle(bob, "bob");

        aliceClient.sendGlobalMessage("   ");    // blank: the server must ignore it
        aliceClient.sendGlobalMessage("marker"); // this one must arrive

        Message received = bob.awaitType(MessageType.GLOBAL_MESSAGE);
        assertEquals("marker", received.content(),
                "the blank message must be dropped, so the first global Bob sees is the marker");
    }

    @Test
    @DisplayName("an over-long global message is capped before broadcast")
    void longGlobalMessageIsCapped() throws Exception {
        RecordingListener alice = new RecordingListener();
        RecordingListener bob = new RecordingListener();
        ChatClient aliceClient = connectAndSettle(alice, "alice");
        connectAndSettle(bob, "bob");

        String huge = "x".repeat(Protocol.MAX_MESSAGE_LENGTH + 500);
        aliceClient.send(Message.global("alice", huge)); // bypass the UI's length cap

        Message received = bob.awaitType(MessageType.GLOBAL_MESSAGE);
        assertEquals(Protocol.MAX_MESSAGE_LENGTH, received.content().length(),
                "the server should truncate the message to the shared maximum length");
    }

    @Test
    @DisplayName("a multi-line global message reaches peers with its newlines intact")
    void multiLineGlobalMessageIsPreserved() throws Exception {
        RecordingListener alice = new RecordingListener();
        RecordingListener bob = new RecordingListener();
        ChatClient aliceClient = connectAndSettle(alice, "alice");
        connectAndSettle(bob, "bob");

        aliceClient.sendGlobalMessage("first line\nsecond line");

        Message received = bob.awaitType(MessageType.GLOBAL_MESSAGE);
        assertEquals("first line\nsecond line", received.content(),
                "newlines typed with Shift+Enter must survive the round-trip through the server");
    }

    @Test
    @DisplayName("the server handles multiple concurrent clients")
    void serverHandlesMultipleClients() throws Exception {
        RecordingListener alice = new RecordingListener();
        RecordingListener bob = new RecordingListener();
        RecordingListener carol = new RecordingListener();

        connectAndSettle(alice, "alice");
        connectAndSettle(bob, "bob");
        connectAndSettle(carol, "carol");

        waitUntil(() -> server.clientManager().size() == 3);
        assertEquals(List.of("alice", "bob", "carol"), server.clientManager().usernames());
    }

    @Test
    @DisplayName("a local disconnect is handled safely and notifies peers")
    void localDisconnectIsHandledSafely() throws Exception {
        RecordingListener alice = new RecordingListener();
        RecordingListener bob = new RecordingListener();
        ChatClient aliceClient = connectAndSettle(alice, "alice");
        connectAndSettle(bob, "bob");

        aliceClient.disconnect();

        Message left = bob.awaitType(MessageType.USER_LEFT);
        assertEquals("alice", left.sender());

        assertTrue(alice.disconnected.await(AWAIT_MILLIS, TimeUnit.MILLISECONDS),
                "the disconnecting client should be notified");
        assertEquals("disconnected", alice.disconnectReason);
        assertFalse(aliceClient.isConnected());
        waitUntil(() -> server.clientManager().size() == 1);

        // Idempotent: a second disconnect must be a harmless no-op.
        aliceClient.disconnect();
        assertEquals(1, alice.disconnectCount.get(), "onDisconnected must fire exactly once");
    }

    @Test
    @DisplayName("a server-side shutdown notifies the connected client")
    void serverShutdownNotifiesClient() throws Exception {
        RecordingListener alice = new RecordingListener();
        ChatClient client = connectAndSettle(alice, "alice");

        server.shutdown();

        assertTrue(alice.disconnected.await(AWAIT_MILLIS, TimeUnit.MILLISECONDS),
                "client should be notified when the server closes the connection");
        assertFalse(client.isConnected());
    }

    @Test
    @DisplayName("connecting to a closed port fails cleanly")
    void connectingToClosedPortFails() throws IOException {
        int deadPort;
        try (ServerSocket probe = new ServerSocket(0)) {
            deadPort = probe.getLocalPort();
        } // port is released here, so nothing is listening on it

        RecordingListener listener = new RecordingListener();
        ChatClient client = new ChatClient(listener, 1_000);

        assertThrows(IOException.class, () -> client.connect("127.0.0.1", deadPort, "ghost"));
        assertFalse(client.isConnected(), "a failed connect must leave the client disconnected");
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private ChatClient connectAndSettle(RecordingListener listener, String username) throws Exception {
        ChatClient client = new ChatClient(listener);
        clients.add(client);
        client.connect("127.0.0.1", port, username);

        assertTrue(listener.connected.await(AWAIT_MILLIS, TimeUnit.MILLISECONDS),
                "onConnected should fire for " + username);
        listener.awaitType(MessageType.LOGIN_SUCCESS);
        listener.awaitType(MessageType.USER_LIST);
        return client;
    }

    private void waitUntil(BooleanSupplier condition) {
        long deadline = System.currentTimeMillis() + AWAIT_MILLIS;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            sleep();
        }
        fail("condition not met within " + AWAIT_MILLIS + "ms");
    }

    private static void sleep() {
        try {
            Thread.sleep(20);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * A thread-safe {@link ChatClientListener} that records callbacks (which fire
     * on the client's background threads) so the test thread can await them.
     */
    private static final class RecordingListener implements ChatClientListener {

        final BlockingQueue<Message> inbox = new LinkedBlockingQueue<>();
        final CountDownLatch connected = new CountDownLatch(1);
        final CountDownLatch disconnected = new CountDownLatch(1);
        final AtomicInteger disconnectCount = new AtomicInteger();
        volatile String disconnectReason;
        volatile Throwable lastError;

        @Override
        public void onConnected() {
            connected.countDown();
        }

        @Override
        public void onMessage(Message message) {
            inbox.add(message);
        }

        @Override
        public void onDisconnected(String reason) {
            disconnectReason = reason;
            disconnectCount.incrementAndGet();
            disconnected.countDown();
        }

        @Override
        public void onError(Throwable error) {
            lastError = error;
        }

        /** Reads (skipping other types) until a message of {@code type} arrives. */
        Message awaitType(MessageType type) throws InterruptedException {
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(AWAIT_MILLIS);
            List<MessageType> seen = new ArrayList<>();
            while (true) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    fail("timed out waiting for " + type + "; saw " + seen);
                }
                Message message = inbox.poll(remaining, TimeUnit.NANOSECONDS);
                if (message == null) {
                    fail("timed out waiting for " + type + "; saw " + seen);
                }
                if (message.type() == type) {
                    return message;
                }
                seen.add(message.type());
            }
        }

        /** Reads the next message, waiting up to {@code timeoutMillis}. */
        Message poll(long timeoutMillis) throws InterruptedException {
            return inbox.poll(timeoutMillis, TimeUnit.MILLISECONDS);
        }
    }
}
