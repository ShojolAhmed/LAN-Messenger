package com.lanmessenger.client.ui;

import com.lanmessenger.client.ui.components.ChatHeader;
import com.lanmessenger.client.ui.components.MessageComposer;
import com.lanmessenger.client.ui.components.MessageListView;
import com.lanmessenger.client.ui.components.Sidebar;
import com.lanmessenger.client.ui.components.StatusIndicator;
import com.lanmessenger.client.ui.components.TitleBar;
import com.lanmessenger.client.ui.model.ChatMessage;
import com.lanmessenger.client.ui.model.Conversation;
import com.lanmessenger.client.ui.model.ConversationKind;
import com.lanmessenger.common.Protocol;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;

/**
 * The root of the messenger UI: the title bar on top, and below it a two-column
 * split of the {@link Sidebar} and the chat pane (header, transcript, composer).
 *
 * <p>This class is pure composition and wiring; every visual decision lives in the
 * reusable components and {@code theme.css}. It hosts the <b>global chat</b>: the
 * single shared room every logged-in user is in.
 *
 * <h2>Message flow</h2>
 * <ul>
 *   <li><b>Sending</b> &mdash; the composer hands submitted text to
 *       {@link #onSendGlobal}, which forwards it to the server; the message is
 *       also echoed into the transcript immediately, because the server broadcasts
 *       a global message to everyone <em>except</em> its sender.</li>
 *   <li><b>Receiving</b> &mdash; {@link com.lanmessenger.client.ClientController}
 *       calls {@link #receiveGlobalMessage(String, String, LocalDateTime)} for each
 *       inbound {@code GLOBAL_MESSAGE}. A message from another user renders as an
 *       incoming bubble; one whose sender matches {@link #currentUsername} renders
 *       as our own (outgoing) bubble.</li>
 *   <li><b>Presence</b> &mdash; {@link #setOnlineUsers(List)},
 *       {@link #noteUserJoined(String)} and {@link #noteUserLeft(String)} keep the
 *       header's online count current and drop a quiet system notice into the room
 *       when people come and go.</li>
 * </ul>
 *
 * <p>All of these methods must be called on the JavaFX Application Thread; the
 * controller already marshals the networking callbacks onto it.
 */
public final class MainView extends BorderPane {

    private static final String GLOBAL_ROOM_TITLE = "Global Chat";

    private final TitleBar titleBar = new TitleBar(Protocol.APP_NAME);
    private final ChatHeader chatHeader = new ChatHeader();
    private final MessageListView messageList = new MessageListView();
    private final MessageComposer composer = new MessageComposer();

    private final String currentUsername;
    private final Consumer<String> onSendGlobal;
    private final Conversation global;
    private final Set<String> onlineUsers = new TreeSet<>();

    /**
     * @param currentUsername the logged-in user's name, used to tell our own
     *                        messages apart from everyone else's
     * @param onSendGlobal    where composed messages are sent (typically
     *                        {@code ChatClient::sendGlobalMessage})
     */
    public MainView(String currentUsername, Consumer<String> onSendGlobal) {
        this.currentUsername = currentUsername == null ? "" : currentUsername;
        this.onSendGlobal = onSendGlobal == null ? text -> { } : onSendGlobal;

        getStyleClass().add("app-root");

        global = Conversation.builder("global", ConversationKind.GLOBAL, GLOBAL_ROOM_TITLE)
                .headerSubtitle("Everyone on the LAN")
                .sidebarSubtitle("Public channel \u00b7 everyone")
                .messages(List.of(ChatMessage.system(
                        "This is the beginning of the Global channel.", LocalDateTime.now())))
                .build();

        Sidebar sidebar = new Sidebar(List.of(global));

        // Chat column: header (fixed), transcript (grows), composer (fixed).
        VBox chatPane = new VBox(chatHeader, messageList, composer);
        chatPane.getStyleClass().add("chat-pane");
        VBox.setVgrow(messageList, Priority.ALWAYS);
        HBox.setHgrow(chatPane, Priority.ALWAYS);

        HBox split = new HBox(sidebar, chatPane);
        split.getStyleClass().add("content-split");

        setTop(titleBar);
        setCenter(split);

        sidebar.setOnConversationSelected(this::openConversation);
        composer.setOnSend(this::sendGlobal);

        // Seed the roster with ourselves so the count is sensible before the first
        // USER_LIST arrives from the server.
        if (!this.currentUsername.isEmpty()) {
            onlineUsers.add(this.currentUsername);
        }
        sidebar.selectFirst(); // opens the global room
        updateHeaderSubtitle();
    }

    /** Focuses the composer input; call once the window is shown. */
    public void focusComposer() {
        composer.focusInput();
    }

    /** Reflects a live connection in the title bar's status pill. */
    public void showConnected() {
        titleBar.status().setState(StatusIndicator.State.CONNECTED);
    }

    /** Reflects a lost/closed connection in the title bar's status pill. */
    public void showDisconnected() {
        titleBar.status().setState(StatusIndicator.State.DISCONNECTED);
    }

    // ---------------------------------------------------------------------
    // Inbound global chat
    // ---------------------------------------------------------------------

    /**
     * Appends a received global message to the transcript. A message from another
     * user is shown as an incoming bubble; one whose {@code sender} is us renders
     * as our own bubble (a defensive case &mdash; the server does not echo a
     * sender's own broadcast, which is why {@link #sendGlobal(String)} shows it
     * locally instead).
     *
     * @param sender  the message author as stamped by the server
     * @param content the message text
     * @param at      when the message was received
     */
    public void receiveGlobalMessage(String sender, String content, LocalDateTime at) {
        boolean own = !currentUsername.isEmpty() && currentUsername.equals(sender);
        ChatMessage message = own
                ? ChatMessage.outgoing(content, at)
                : ChatMessage.incoming(sender, content, at);
        global.messages().add(message);
    }

    /** Records that {@code username} joined the room: updates the count and notes it. */
    public void noteUserJoined(String username) {
        if (username == null || username.isBlank()) {
            return;
        }
        if (onlineUsers.add(username)) {
            updateHeaderSubtitle();
        }
        global.messages().add(ChatMessage.system(username + " joined the channel.", LocalDateTime.now()));
    }

    /** Records that {@code username} left the room: updates the count and notes it. */
    public void noteUserLeft(String username) {
        if (username == null || username.isBlank()) {
            return;
        }
        if (onlineUsers.remove(username)) {
            updateHeaderSubtitle();
        }
        global.messages().add(ChatMessage.system(username + " left the channel.", LocalDateTime.now()));
    }

    /**
     * Replaces the known roster with the server's authoritative list (always
     * including ourselves) and refreshes the header's online count.
     *
     * @param usernames the current online usernames
     */
    public void setOnlineUsers(List<String> usernames) {
        onlineUsers.clear();
        if (usernames != null) {
            onlineUsers.addAll(usernames);
        }
        if (!currentUsername.isEmpty()) {
            onlineUsers.add(currentUsername);
        }
        updateHeaderSubtitle();
    }

    // ---------------------------------------------------------------------
    // Internals
    // ---------------------------------------------------------------------

    private void openConversation(Conversation conversation) {
        chatHeader.setConversation(conversation);
        messageList.setConversation(conversation);
        updateHeaderSubtitle(); // keep the live online count after (re)opening
    }

    private void sendGlobal(String text) {
        // Send to the server, then echo locally: the server broadcasts to everyone
        // *except* the sender, so this is the only place our own bubble appears.
        onSendGlobal.accept(text);
        global.messages().add(ChatMessage.outgoing(text, LocalDateTime.now()));
    }

    private void updateHeaderSubtitle() {
        int online = onlineUsers.size();
        chatHeader.setSubtitle("Everyone on the LAN \u00b7 " + online + " online");
    }
}
