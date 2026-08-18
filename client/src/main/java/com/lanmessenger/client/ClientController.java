package com.lanmessenger.client;

import com.lanmessenger.client.net.ChatClient;
import com.lanmessenger.client.net.ChatClientListener;
import com.lanmessenger.client.net.ConnectionValidator;
import com.lanmessenger.client.history.ChatHistory;
import com.lanmessenger.client.ui.ConnectView;
import com.lanmessenger.client.ui.MainView;
import com.lanmessenger.common.Message;
import javafx.animation.PauseTransition;
import javafx.concurrent.Task;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.util.Duration;

import java.util.Objects;
import java.time.LocalDateTime;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Orchestrates the connection / login flow and the transition into the messenger.
 *
 * <p>It owns the two top-level screens&mdash;the {@link ConnectView} shown first
 * and the {@link MainView} shown once logged in&mdash;and the {@link ChatClient}
 * used to reach the server. It is the single {@link ChatClientListener} for the
 * connection; because it is wrapped in a {@link FxChatClientListener}, every
 * callback here runs on the JavaFX Application Thread and may touch controls
 * directly.
 *
 * <h2>Keeping the UI responsive</h2>
 * <p>{@link ChatClient#connect(String, int, String)} blocks for the TCP handshake,
 * so it is run on a background {@link Task}; the FX thread is never blocked while
 * connecting. The button and fields show a "connecting" state until the outcome is
 * known.
 *
 * <h2>Flow</h2>
 * <ol>
 *   <li>The user submits the form. Inputs are validated with
 *       {@link ConnectionValidator}; any error is shown immediately with no
 *       network access.</li>
 *   <li>On valid input the view enters its connecting state and a background task
 *       opens the socket and sends {@code LOGIN}.</li>
 *   <li>If the socket cannot be opened, the task fails and the view shows
 *       "Unable to connect to server."</li>
 *   <li>Otherwise the server's verdict arrives as a message: {@code LOGIN_SUCCESS}
 *       transitions to the messenger; {@code LOGIN_FAILED} (e.g. a duplicate
 *       username) shows the reason and lets the user try again.</li>
 * </ol>
 */
public final class ClientController implements ChatClientListener {

    private static final Logger LOG = Logger.getLogger(ClientController.class.getName());

    /** How long to wait for the server's login verdict before giving up. */
    private static final Duration LOGIN_TIMEOUT = Duration.seconds(10);

    /** The stage of the flow, which decides how each callback is interpreted. */
    private enum Phase {
        /** On the connect screen, idle. */
        CONNECT,
        /** Opening the socket on a background task. */
        CONNECTING,
        /** Socket open, {@code LOGIN} sent, waiting for the server's verdict. */
        AWAITING_LOGIN,
        /** Logged in; the messenger is shown. */
        IN_APP
    }

    private final ConnectView connectView = new ConnectView();

    private MainView mainView;      // created fresh on each successful login
    private Scene scene;            // set via attachScene, used to swap the root
    private ChatClient client;      // recreated per connection attempt

    private Phase phase = Phase.CONNECT;
    private boolean suppressNextDisconnect;
    private PauseTransition loginTimeout;

    private String pendingUsername;
    private String pendingHost;

    public ClientController() {
        connectView.setOnConnect(this::attemptConnect);
    }

    /** @return the initial root node (the connect screen). */
    public Parent root() {
        return connectView;
    }

    /** Supplies the scene whose root is swapped when moving between screens. */
    public void attachScene(Scene scene) {
        this.scene = Objects.requireNonNull(scene, "scene");
    }

    /** Focuses the first field; call once the window is shown. */
    public void start() {
        connectView.focusUsername();
    }

    /**
     * Cleanly tears everything down when the application is closing: cancels any
     * pending login watchdog, disconnects the client (sending a best-effort
     * {@code DISCONNECT} so the server drops us promptly), and disposes the
     * messenger so its chat-history store closes. Safe to call when idle. Runs on
     * the JavaFX Application Thread from {@link com.lanmessenger.client.ClientApp#stop()}.
     */
    public void shutdown() {
        cancelLoginTimeout();
        // We are exiting; there is no UI transition to perform for this disconnect.
        suppressNextDisconnect = true;
        if (client != null) {
            client.disconnect();
            client = null;
        }
        if (mainView != null) {
            mainView.dispose();
            mainView = null;
        }
        phase = Phase.CONNECT;
    }

    // ---------------------------------------------------------------------
    // Connect flow
    // ---------------------------------------------------------------------

    private void attemptConnect() {
        if (phase != Phase.CONNECT) {
            return; // already connecting/logged in; ignore stray submits
        }

        ConnectionValidator.Result result =
                ConnectionValidator.validate(connectView.username(), connectView.host(), connectView.portText());
        if (!result.valid()) {
            connectView.showError(result.error(), result.field());
            return;
        }

        connectView.clearError();
        connectView.setConnecting(true);
        phase = Phase.CONNECTING;
        pendingUsername = result.username();
        pendingHost = result.host();

        startConnectTask(result.host(), result.port(), result.username());
    }

    private void startConnectTask(String host, int port, String username) {
        client = new ChatClient(new FxChatClientListener(this));

        Task<Void> task = new Task<>() {
            @Override
            protected Void call() throws Exception {
                client.connect(host, port, username);
                return null;
            }
        };
        task.setOnSucceeded(event -> onTransportConnected());
        task.setOnFailed(event -> onConnectFailed(task.getException()));

        Thread thread = new Thread(task, "lan-connect");
        thread.setDaemon(true);
        thread.start();
    }

    /** The socket opened and {@code LOGIN} was sent; wait for the verdict. */
    private void onTransportConnected() {
        if (phase == Phase.CONNECTING) {
            phase = Phase.AWAITING_LOGIN;
            startLoginTimeout();
        }
    }

    /** {@link ChatClient#connect} threw, so no further callbacks will arrive. */
    private void onConnectFailed(Throwable error) {
        LOG.log(Level.FINE, error, () -> "Connection attempt failed");
        client = null;
        resetToConnect("Unable to connect to server.");
    }

    // ---------------------------------------------------------------------
    // ChatClientListener — all callbacks run on the FX thread
    // ---------------------------------------------------------------------

    @Override
    public void onConnected() {
        // Transport-level success is handled by the task's onSucceeded; nothing to
        // do here. The login verdict still follows as a message.
    }

    @Override
    public void onMessage(Message message) {
        // Until we are logged in, the only messages we act on are the login verdict.
        if (phase == Phase.CONNECTING || phase == Phase.AWAITING_LOGIN) {
            switch (message.type()) {
                case LOGIN_SUCCESS -> enterApp();
                case LOGIN_FAILED -> failLogin(message.content());
                default -> { /* ignore anything before the verdict (e.g. USER_LIST) */ }
            }
            return;
        }
        // Once inside the messenger, drive the global chat from live server events.
        if (phase == Phase.IN_APP && mainView != null) {
            routeToMessenger(message);
        }
    }

    /** Feeds an inbound message into the messenger while logged in. */
    private void routeToMessenger(Message message) {
        switch (message.type()) {
            case GLOBAL_MESSAGE ->
                    mainView.receiveGlobalMessage(message.sender(), message.content(), LocalDateTime.now());
            case PRIVATE_MESSAGE ->
                    mainView.receivePrivateMessage(message.sender(), message.content(), LocalDateTime.now());
            case USER_JOINED -> mainView.noteUserJoined(message.sender());
            case USER_LEFT -> mainView.noteUserLeft(message.sender());
            case USER_LIST -> mainView.setOnlineUsers(message.userListEntries());
            case ERROR -> {
                // A delivery error tagged with a recipient (e.g. a private message to
                // someone who went offline) is surfaced in that conversation; generic,
                // un-attributed errors are not shown to the user.
                if (!message.recipient().isEmpty()) {
                    mainView.notePrivateDeliveryFailure(message.recipient(), message.content());
                }
            }
            default -> { /* other types are not surfaced in the messenger */ }
        }
    }

    @Override
    public void onDisconnected(String reason) {
        if (suppressNextDisconnect) {
            suppressNextDisconnect = false; // our own teardown; already handled
            return;
        }
        switch (phase) {
            case CONNECTING, AWAITING_LOGIN -> {
                client = null;
                resetToConnect("Unable to connect to server.");
            }
            case IN_APP -> returnToConnect("Connection lost. Please reconnect.");
            case CONNECT -> { /* nothing to do */ }
        }
    }

    @Override
    public void onError(Throwable error) {
        // Connection-fatal errors are immediately followed by onDisconnected, which
        // updates the UI. Nothing user-facing to do here on its own.
        LOG.log(Level.FINE, error, () -> "Networking error");
    }

    // ---------------------------------------------------------------------
    // Transitions
    // ---------------------------------------------------------------------

    private void enterApp() {
        cancelLoginTimeout();
        phase = Phase.IN_APP;

        // Defensive: release any previous session's messenger (and its history store)
        // before building a new one.
        if (mainView != null) {
            mainView.dispose();
        }

        // A fresh messenger per login: it binds to the current connection's send
        // methods and to a SQLite-backed chat history scoped to this user. Persisted
        // messages load in the background; new ones are recorded as they flow.
        ChatHistory history = ChatHistory.open(pendingUsername);
        mainView = new MainView(pendingUsername, client::sendGlobalMessage, client::sendPrivateMessage, history);
        mainView.showConnected();

        if (scene != null) {
            scene.setRoot(mainView);
        }
        mainView.focusComposer();
        LOG.info(() -> "Logged in as '" + pendingUsername + "' to " + pendingHost);
    }

    private void failLogin(String reason) {
        cancelLoginTimeout();
        // The server keeps the socket open after a rejected login, but this client
        // reconnects from scratch on retry, so drop the current connection cleanly.
        disconnectQuietly();
        resetToConnect(describeLoginFailure(reason));
        connectView.focusUsername();
    }

    /** Restores the connect screen's idle state and shows {@code message}. */
    private void resetToConnect(String message) {
        cancelLoginTimeout();
        phase = Phase.CONNECT;
        connectView.setConnecting(false);
        connectView.showError(message);
    }

    /** Returns from the messenger to the connect screen (e.g. after a drop). */
    private void returnToConnect(String message) {
        cancelLoginTimeout();
        phase = Phase.CONNECT;
        client = null;
        if (mainView != null) {
            mainView.showDisconnected();
            mainView.dispose(); // close the chat-history store for this session
            mainView = null;
        }
        connectView.setConnecting(false);
        connectView.showError(message);
        if (scene != null) {
            scene.setRoot(connectView);
        }
        connectView.focusUsername();
    }

    private void disconnectQuietly() {
        if (client != null) {
            suppressNextDisconnect = true; // ignore the onDisconnected our call triggers
            client.disconnect();
            client = null;
        }
    }

    // ---------------------------------------------------------------------
    // Login-response watchdog
    // ---------------------------------------------------------------------

    private void startLoginTimeout() {
        cancelLoginTimeout();
        loginTimeout = new PauseTransition(LOGIN_TIMEOUT);
        loginTimeout.setOnFinished(event -> {
            if (phase == Phase.AWAITING_LOGIN) {
                disconnectQuietly();
                resetToConnect("Unable to connect to server.");
            }
        });
        loginTimeout.play();
    }

    private void cancelLoginTimeout() {
        if (loginTimeout != null) {
            loginTimeout.stop();
            loginTimeout = null;
        }
    }

    private static String describeLoginFailure(String reason) {
        if (reason == null || reason.isBlank()) {
            return "Login was rejected by the server.";
        }
        // Server reasons are lower-case fragments (e.g. "username 'x' is already
        // taken"); present them as a tidy sentence.
        String trimmed = reason.trim();
        String sentence = Character.toUpperCase(trimmed.charAt(0)) + trimmed.substring(1);
        return sentence.endsWith(".") ? sentence : sentence + ".";
    }
}
